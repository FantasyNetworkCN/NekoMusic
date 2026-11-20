package com.neko.music.service;

import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.DataInputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Reference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static java.lang.foreign.ValueLayout.*;

/**
 * Rust 所有的经典声纹索引。FFM 每次批量传入查询，posting 不在 Java 堆里留副本。
 * 自动 Arena 释放原生 Arc；查询期间通过 reachabilityFence 保证句柄仍然存活。
 */
final class RustFingerprintIndex extends FingerprintPostings {
    private static final Bindings NATIVE = load();
    private final Arena owner = Arena.ofAuto();
    private final MemorySegment handle;
    private final MemorySegment slots;
    private final MemorySegment values;
    private final RustFingerprintIndex base;
    private final int capacity;
    private final int size;
    private final long count;
    private final long nativeBytes;

    static RustFingerprintIndex create(Map<Long, long[]> additions, RustFingerprintIndex base) {
        return new RustFingerprintIndex(additions, base);
    }

    private RustFingerprintIndex(Map<Long, long[]> additions, RustFingerprintIndex base) {
        this(additions.size(), count(additions), base, (slots, values, capacity) -> {
            long offset = 0;
            for (var entry : additions.entrySet()) {
                long[] posting = entry.getValue();
                insert(slots, capacity, entry.getKey(), offset, posting.length);
                MemorySegment.copy(posting, 0, values, JAVA_LONG, offset * 8, posting.length);
                offset += posting.length;
            }
        });
    }

    /** 从持久化流直接写入原生区域，不在堆里重建全量 Map 与 posting 数组。 */
    static RustFingerprintIndex read(DataInputStream input, int hashes, long postings, Set<Integer> musicIds)
            throws IOException {
        try {
            return new RustFingerprintIndex(hashes, postings, null, (slots, values, capacity) -> {
                long offset = 0;
                for (int i = 0; i < hashes; i++) {
                    long hash = input.readLong();
                    int length = input.readInt();
                    if (length <= 0 || length > 10_000_000 || offset + length > postings) {
                        throw new IOException("invalid hash posting count");
                    }
                    insert(slots, capacity, hash, offset, length);
                    for (int j = 0; j < length; j++) {
                        long posting = input.readLong();
                        if (!musicIds.contains((int) (posting >>> 32))) {
                            throw new IOException("posting references unknown music id");
                        }
                        values.set(JAVA_LONG, (offset + j) * 8, posting);
                    }
                    offset += length;
                }
                if (offset != postings) {
                    throw new IOException("invalid total posting count");
                }
            });
        } catch (RuntimeException error) {
            if (error.getCause() instanceof IOException io) {
                throw io;
            }
            throw error;
        }
    }

    private static void insert(MemorySegment slots, int capacity, long hash, long offset, int length)
            throws IOException {
        int slot = (int) nativeBucket(hash) & (capacity - 1);
        while (slots.get(JAVA_INT, slot * 16L + 12) != 0) {
            if (slots.get(JAVA_LONG, slot * 16L) == hash) {
                throw new IOException("duplicate hash in persistent index");
            }
            slot = (slot + 1) & (capacity - 1);
        }
        slots.set(JAVA_LONG, slot * 16L, hash);
        slots.set(JAVA_INT, slot * 16L + 8, Math.toIntExact(offset));
        slots.set(JAVA_INT, slot * 16L + 12, length);
    }

    private static long count(Map<Long, long[]> additions) {
        long ownCount = 0;
        for (long[] posting : additions.values()) {
            if (posting == null || posting.length == 0) {
                throw new IllegalArgumentException("posting list cannot be empty");
            }
            ownCount = Math.addExact(ownCount, posting.length);
        }
        return ownCount;
    }

    @FunctionalInterface
    private interface Writer {
        void write(MemorySegment slots, MemorySegment values, int capacity) throws IOException;
    }

    private RustFingerprintIndex(int hashes, long ownCount, RustFingerprintIndex base, Writer writer) {
        this.base = base;
        MemorySegment created;
        try {
            MemorySegment parent = base == null ? MemorySegment.NULL : base.handle;
            created = (MemorySegment) NATIVE.create.invokeExact((long) hashes, ownCount,
                    parent);
        } catch (Throwable error) {
            throw failed(error);
        } finally {
            Reference.reachabilityFence(base);
        }
        if (created.address() == 0) {
            throw new OutOfMemoryError("Rust 声纹索引分配失败");
        }
        boolean initialized = false;
        boolean cleanupRegistered = false;
        try {
            capacity = Math.toIntExact((long) NATIVE.capacity.invokeExact(created));
            MemorySegment rawSlots = (MemorySegment) NATIVE.slots.invokeExact(created);
            MemorySegment rawValues = (MemorySegment) NATIVE.values.invokeExact(created);
            MemorySegment writableSlots = rawSlots.reinterpret(capacity * 16L);
            MemorySegment writableValues = rawValues.reinterpret(ownCount * 8L);
            writer.write(writableSlots, writableValues, capacity);
            int status = (int) NATIVE.seal.invokeExact(created, (long) hashes);
            if (status != 0) {
                throw new IllegalStateException("Rust 声纹索引封装失败");
            }
            size = Math.toIntExact((long) NATIVE.size.invokeExact(created));
            count = (long) NATIVE.count.invokeExact(created);
            nativeBytes = (long) NATIVE.bytes.invokeExact(created);
            handle = created.reinterpret(0, owner, RustFingerprintIndex::release);
            cleanupRegistered = true;
            slots = rawSlots.reinterpret(capacity * 16L, owner, null).asReadOnly();
            values = rawValues.reinterpret(ownCount * 8L, owner, null).asReadOnly();
            initialized = true;
        } catch (Throwable error) {
            throw failed(error);
        } finally {
            if (!initialized && !cleanupRegistered) {
                release(created);
            }
        }
    }

    private static long nativeBucket(long hash) {
        hash = (hash ^ (hash >>> 33)) * 0xff51afd7ed558ccdL;
        hash = (hash ^ (hash >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return hash ^ (hash >>> 33);
    }

    Optional<AudioFingerprintEngine.Match> findBest(AudioFingerprintEngine.Fingerprint query,
            int musicCount, int minimum, double confidence) {
        try (Arena request = Arena.ofConfined()) {
            MemorySegment points = request.allocate(query.landmarks().size() * 16L, 8);
            int i = 0;
            for (var landmark : query.landmarks()) {
                points.set(JAVA_LONG, i * 16L, landmark.hash());
                points.set(JAVA_LONG, i * 16L + 8, landmark.timeFrame());
                i++;
            }
            MemorySegment output = request.allocate(24, 8);
            int status = (int) NATIVE.find.invokeExact(handle, points, (long) i, query.sampleCount(),
                    musicCount, minimum, confidence, output);
            if (status < 0) {
                throw new IllegalStateException("Rust 声纹匹配失败");
            }
            if (status == 0) {
                return Optional.empty();
            }
            return Optional.of(new AudioFingerprintEngine.Match(output.get(JAVA_INT, 0),
                    output.get(JAVA_INT, 4), output.get(JAVA_DOUBLE, 8),
                    output.get(JAVA_INT, 16), output.get(JAVA_INT, 20)));
        } catch (Throwable error) {
            throw failed(error);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    long nativeBytes() {
        return nativeBytes;
    }

    @Override
    long[] get(long hash) {
        try (Arena request = Arena.ofConfined()) {
            MemorySegment length = request.allocate(JAVA_LONG);
            MemorySegment found = (MemorySegment) NATIVE.get.invokeExact(handle, hash, length);
            long count = length.get(JAVA_LONG, 0);
            return found.address() == 0 ? null : found.reinterpret(count * 8).toArray(JAVA_LONG);
        } catch (Throwable error) {
            throw failed(error);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    long postingCount() {
        return count;
    }

    private boolean owns(long hash) {
        int mask = capacity - 1;
        int at = (int) nativeBucket(hash) & mask;
        while (slots.get(JAVA_INT, at * 16L + 12) != 0) {
            if (slots.get(JAVA_LONG, at * 16L) == hash) {
                return true;
            }
            at = (at + 1) & mask;
        }
        return false;
    }

    @Override
    public Set<Entry<Long, long[]>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public int size() {
                return size;
            }

            @Override
            public Iterator<Entry<Long, long[]>> iterator() {
                return new Iterator<>() {
                    private final Iterator<Entry<Long, long[]>> previous =
                            base == null ? Map.<Long, long[]>of().entrySet().iterator() : base.entrySet().iterator();
                    private int nextSlot;
                    private Entry<Long, long[]> next;

                    @Override
                    public boolean hasNext() {
                        if (next != null) {
                            return true;
                        }
                        while (nextSlot < capacity) {
                            int slot = nextSlot++;
                            int length = slots.get(JAVA_INT, slot * 16L + 12);
                            if (length == 0) {
                                continue;
                            }
                            long hash = slots.get(JAVA_LONG, slot * 16L);
                            long offset = Integer.toUnsignedLong(slots.get(JAVA_INT, slot * 16L + 8));
                            next = new SimpleImmutableEntry<>(hash,
                                    values.asSlice(offset * 8, length * 8L).toArray(JAVA_LONG));
                            return true;
                        }
                        while (previous.hasNext()) {
                            var entry = previous.next();
                            if (!owns(entry.getKey())) {
                                next = entry;
                                return true;
                            }
                        }
                        return false;
                    }

                    @Override
                    public Entry<Long, long[]> next() {
                        if (!hasNext()) {
                            throw new NoSuchElementException();
                        }
                        var result = next;
                        next = null;
                        return result;
                    }
                };
            }
        };
    }

    private static void release(MemorySegment handle) {
        try {
            NATIVE.drop.invokeExact(handle);
        } catch (Throwable error) {
            LoggerFactory.getLogger(RustFingerprintIndex.class).error("释放 Rust 声纹索引失败", error);
        }
    }

    private static RuntimeException failed(Throwable error) {
        if (error instanceof Error fatal) {
            throw fatal;
        }
        return error instanceof RuntimeException runtime ? runtime
                : new IllegalStateException("调用 Rust 识曲库失败", error);
    }

    private static Bindings load() {
        try {
            String resource = "/native/linux-x86_64/libneko_recognition.so";
            Path library = null;
            for (Path candidate : new Path[]{
                    Path.of("native/recognition/target/release/libneko_recognition.so"),
                    Path.of("backend/native/recognition/target/release/libneko_recognition.so")}) {
                if (Files.isRegularFile(candidate)) {
                    library = candidate.toAbsolutePath();
                    break;
                }
            }
            if (library == null) {
                try (var input = RustFingerprintIndex.class.getResourceAsStream(resource)) {
                    if (input == null) {
                        throw new IOException("未找到内嵌 Rust 识曲库");
                    }
                    library = Files.createTempFile("neko-recognition-", ".so");
                    Files.copy(input, library, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    library.toFile().deleteOnExit();
                }
            }
            Bindings bindings = new Bindings(SymbolLookup.libraryLookup(library, Arena.global()));
            LoggerFactory.getLogger(RustFingerprintIndex.class).info("经典声纹索引与匹配已启用 Rust 引擎");
            return bindings;
        } catch (Throwable error) {
            if (error instanceof Error fatal) {
                throw fatal;
            }
            throw new IllegalStateException("Rust 识曲库不可用，请检查原生库打包与运行环境", error);
        }
    }

    private static final class Bindings {
        private final MethodHandle create, values, slots, capacity, size, count, bytes, seal, drop, get, find;

        private Bindings(SymbolLookup symbols) throws Throwable {
            var linker = Linker.nativeLinker();
            MethodHandle abi = linker.downcallHandle(symbols.findOrThrow("neko_index_abi"),
                    FunctionDescriptor.of(JAVA_INT));
            if ((int) abi.invokeExact() != 1) {
                throw new IllegalStateException("Rust 识曲 ABI 版本不匹配");
            }
            create = bind(symbols, "new", FunctionDescriptor.of(ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS));
            values = bind(symbols, "values", FunctionDescriptor.of(ADDRESS, ADDRESS));
            slots = bind(symbols, "slots", FunctionDescriptor.of(ADDRESS, ADDRESS));
            capacity = bind(symbols, "capacity", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
            size = bind(symbols, "size", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
            count = bind(symbols, "count", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
            bytes = bind(symbols, "bytes", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
            seal = bind(symbols, "seal", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
            drop = bind(symbols, "drop", FunctionDescriptor.ofVoid(ADDRESS));
            get = bind(symbols, "get", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
            find = bind(symbols, "find", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG,
                    JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_DOUBLE, ADDRESS));
        }

        private static MethodHandle bind(SymbolLookup symbols, String name, FunctionDescriptor descriptor) {
            return Linker.nativeLinker().downcallHandle(symbols.findOrThrow("neko_index_" + name), descriptor);
        }
    }
}
