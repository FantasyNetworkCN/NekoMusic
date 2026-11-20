package com.neko.music.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.ref.Reference;
import java.util.HashMap;
import java.util.Map;
import java.io.DataOutputStream;
import java.io.BufferedOutputStream;

/** Linux 独立进程的稳态 RSS 探针，包含 JVM 与 Rust 库；峰值单独报告。 */
public final class IndexMemoryBenchmark {
    private static volatile Object retained;

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 3 && arguments[0].equals("--fixture")) {
            fixture(Integer.parseInt(arguments[1]), Path.of(arguments[2]));
            return;
        }
        int hashes = arguments.length == 0 ? 1_000_000 : Integer.parseInt(arguments[0]);
        settle();
        long before = status("VmRSS");
        long started = System.nanoTime();
        var index = arguments.length > 1
                ? new MusicRecognitionService.FullIndexDiskCache(Path.of(arguments[1]))
                        .load().orElseThrow().index()
                : build(hashes);
        double loadMillis = (System.nanoTime() - started) / 1_000_000d;
        retained = index;
        settle();
        long after = status("VmRSS");
        long peak = status("VmHWM");
        long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        System.out.printf(java.util.Locale.ROOT,
                "index_process hashes=%d idle_rss_kib=%d index_rss_kib=%d delta_rss_kib=%d peak_rss_kib=%d heap_used=%d heap_committed=%d load_ms=%.3f%n",
                index.uniqueHashCount(), before, after, after - before, peak,
                heap, Runtime.getRuntime().totalMemory(), loadMillis);
        if (System.getenv("NEKO_BENCH_NMT") != null) {
            new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "jcmd").toString(),
                    Long.toString(ProcessHandle.current().pid()), "VM.native_memory", "summary")
                    .inheritIO().start().waitFor();
        }
        Reference.reachabilityFence(retained);
    }

    /** 在独立进程生成兼容持久化文件，避免构造测试输入影响被测进程 RSS。 */
    private static void fixture(int hashes, Path file) throws Exception {
        try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            output.writeInt(0x4e464931);
            output.writeInt(3);
            output.writeInt(AudioFingerprintEngine.ALGORITHM_VERSION);
            output.writeInt(4);
            for (int track = 1; track <= 4; track++) {
                output.writeInt(track);
                for (String text : new String[]{"Track", "Artist", "Album"}) {
                    byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    output.writeInt(bytes.length);
                    output.write(bytes);
                }
                output.writeInt(8);
                output.writeInt(0);
                output.writeInt(0);
                output.writeInt(0);
                output.writeBoolean(false);
            }
            output.writeInt(4);
            for (int track = 1; track <= 4; track++) {
                output.writeInt(track);
            }
            output.writeInt(hashes);
            for (int i = 0; i < hashes; i++) {
                output.writeLong((i * 0x9e3779b9L) & ((1L << 34) - 1));
                output.writeInt(4);
                for (int track = 1; track <= 4; track++) {
                    output.writeLong(((long) track << 32) | i);
                }
            }
            output.writeInt(0);
            output.writeInt(0);
        }
    }

    private static AudioFingerprintEngine.Index build(int hashes) {
        Map<Long, long[]> stored = new HashMap<>();
        for (int i = 0; i < hashes; i++) {
            long hash = (i * 0x9e3779b9L) & ((1L << 34) - 1);
            stored.put(hash, new long[]{(1L << 32) | i, (2L << 32) | i,
                    (3L << 32) | i, (4L << 32) | i});
        }
        return AudioFingerprintEngine.Index.restore(stored, 4);
    }

    private static void settle() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(100);
        }
    }

    private static long status(String field) throws Exception {
        for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
            if (line.startsWith(field + ":")) {
                return Long.parseLong(line.substring(field.length() + 1).trim().split("\\s+")[0]);
            }
        }
        throw new IllegalStateException("无法读取 " + field);
    }
}
