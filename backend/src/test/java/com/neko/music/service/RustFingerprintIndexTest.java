package com.neko.music.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class RustFingerprintIndexTest {
    @TempDir
    Path temporary;

    @Test
    void loadsBundledLibraryWithoutSourceTreeOrCargoAtRuntime() throws Exception {
        Path output = temporary.resolve("probe.log");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                PackagedProbe.class.getName())
                .directory(temporary.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), () -> {
            try {
                return Files.readString(output);
            } catch (java.io.IOException error) {
                return error.toString();
            }
        });
    }

    public static final class PackagedProbe {
        public static void main(String[] arguments) {
            var index = AudioFingerprintEngine.Index.restore(Map.of(9L, new long[]{posting(1, 100)}), 1);
            if (!index.usesRust() || index.nativeBytes() == 0) {
                throw new AssertionError("内嵌 Rust 库未加载");
            }
            if (index.postingsView().get(9L)[0] != posting(1, 100)) {
                throw new AssertionError("内嵌 Rust 库读取错误");
            }
        }
    }

    @Test
    void defaultIndexActuallyOwnsItsDataInRust() {
        long[] original = {posting(1, 100), posting(2, 200)};
        AudioFingerprintEngine.Index index = AudioFingerprintEngine.Index.restore(
                Map.of(0L, original, Long.MIN_VALUE, new long[]{posting(3, 300)}), 3);
        assertTrue(index.usesRust(), "默认测试必须加载真实 Rust 库，不能以 Java 降级掩盖打包错误");
        assertTrue(index.nativeBytes() > 0);
        original[0] = 999;
        assertArrayEquals(new long[]{posting(1, 100), posting(2, 200)}, index.postingsView().get(0L));
        assertArrayEquals(new long[]{posting(3, 300)}, index.postingsView().get(Long.MIN_VALUE));
        assertNull(index.postingsView().get(12345L));
        assertEquals(2, index.uniqueHashCount());
    }

    @Test
    void rejectsMalformedStreamBeforePublishingNativeIndex() throws Exception {
        for (boolean duplicate : new boolean[]{false, true}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeLong(9);
                output.writeInt(1);
                output.writeLong(posting(1, 100));
                output.writeLong(duplicate ? 9 : 10);
                output.writeInt(1);
                output.writeLong(posting(duplicate ? 1 : 999, 200));
            }
            assertThrows(IOException.class, () -> RustFingerprintIndex.read(
                    new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),
                    2, 2, java.util.Set.of(1)));
        }
        assertThrows(IOException.class, () -> RustFingerprintIndex.read(
                new DataInputStream(new ByteArrayInputStream(new byte[0])),
                1, 1, java.util.Set.of(1)));
    }

    @Test
    void incrementalNativeIndexRemainsQueryableAcrossCollectionAndConcurrentCalls() throws Exception {
        List<AudioFingerprintEngine.Landmark> points = new ArrayList<>();
        List<AudioFingerprintEngine.Landmark> query = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            points.add(new AudioFingerprintEngine.Landmark(i * 1000L, i * 100 + 500));
            query.add(new AudioFingerprintEngine.Landmark(i * 1000L, i * 100));
        }
        var fingerprint = new AudioFingerprintEngine.Fingerprint(points, 128_000);
        var base = AudioFingerprintEngine.Index.build(Map.of(1, fingerprint));
        var builder = AudioFingerprintEngine.Index.builder(base);
        builder.add(2, new AudioFingerprintEngine.Fingerprint(
                List.of(new AudioFingerprintEngine.Landmark(123456789, 1)), 128_000));
        var updated = builder.build();
        assertTrue(base.usesRust());
        assertTrue(updated.usesRust());
        assertEquals(21, updated.uniqueHashCount());
        assertEquals(20, base.uniqueHashCount());
        assertEquals(21, updated.postingsView().entrySet().stream().count());
        var request = new AudioFingerprintEngine.Fingerprint(query, 128_000);
        var expected = new AudioFingerprintEngine.Match(1, 20, 1d, 500, 0);
        try (var workers = Executors.newFixedThreadPool(8)) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 128; i++) {
                tasks.add(() -> {
                    assertEquals(expected, updated.findBest(request, 6, 0.08).orElseThrow());
                    assertEquals(expected, base.findBest(request, 6, 0.08).orElseThrow());
                    return null;
                });
            }
            System.gc();
            for (var future : workers.invokeAll(tasks)) {
                future.get();
            }
        }
    }

    private static long posting(int music, int time) {
        return ((long) music << 32) | (time & 0xffff_ffffL);
    }
}
