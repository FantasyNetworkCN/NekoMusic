package com.neko.music.search;

import com.neko.music.benchmark.BenchmarkMeter;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 使用真实 SQLite FTS5 索引测量热缓存、缓存抖动与并发等待，不需要 MySQL 或线上曲库。 */
public final class SearchLoadBenchmark {
    private static volatile Object result;

    public static void main(String[] arguments) throws Exception {
        int documents = arguments.length == 0 ? 10000 : Integer.parseInt(arguments[0]);
        Path directory = Files.createTempDirectory("neko-search-load-");
        Path path = directory.resolve("search.db");
        try {
            try (FtsStore store = new FtsStore()) {
                store.open(path.toString(), true);
                for (int i = 0; i < documents; i++) {
                    FtsStore.DocTokens doc = new FtsStore.DocTokens();
                    doc.trackId = Integer.toString(i + 1);
                    doc.titleTok = term(i);
                    doc.artistTok = "commonartist";
                    doc.lyricsTok = "starlight melody dream moon night";
                    doc.indexedMtime = 1;
                    store.put(i + 1, doc);
                }
                FtsStore.StoreMeta meta = new FtsStore.StoreMeta();
                meta.hasHan = false;
                meta.hasJapanese = false;
                meta.hasKorean = false;
                meta.hasLatin = true;
                store.writeMeta(meta);
                store.seal();
            }
            for (int i = 0; i < 32; i++) {
                query(path, term(i), false);
            }
            BenchmarkMeter.measure("search_hot_32_per_query", 32, 5, 30, () -> {
                for (int i = 0; i < 32; i++) {
                    result = query(path, term(i), false);
                }
            });
            SearchSession.closeCached();
            BenchmarkMeter.measure("search_churn_256_per_query", 256, 3, 10, () -> {
                for (int i = 0; i < 256; i++) {
                    result = query(path, term(i), false);
                }
            });
            for (int threads : new int[]{1, 4, 16}) {
                concurrent(path, threads, false);
                concurrent(path, threads, true);
            }
        } finally {
            SearchSession.closeCached();
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }

    private static SearchSession.SearchResult query(Path path, String text, boolean fuzzy) {
        return SearchSession.run(path.toString(), new SearchSession.SearchRequest(
                text, SearchScope.METADATA, 80, 0, fuzzy, 1, FtsStore.DEFAULT_CANDIDATE_CAP));
    }

    private static void concurrent(Path path, int threads, boolean fuzzy) throws Exception {
        SearchSession.closeCached();
        // 预热精确匹配、词典和模糊查询编译，避免把首次词库初始化混入并发等待。
        for (int i = 0; i < 4096; i++) {
            query(path, term(i) + (fuzzy ? "x" : ""), fuzzy);
        }
        SearchSession.debugResetCacheStats();
        // 各并发档位处理相同的 4096 个词条，避免词长和模糊邻域不同影响比较。
        int queries = 4096 / threads;
        long[] latency = new long[threads * queries];
        var bean = ManagementFactory.getThreadMXBean();
        bean.setThreadContentionMonitoringEnabled(true);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<long[]>> pending = new ArrayList<>();
        long started;
        try (var executor = Executors.newFixedThreadPool(threads)) {
            for (int worker = 0; worker < threads; worker++) {
                int first = worker * queries;
                pending.add(executor.submit(() -> {
                    long id = Thread.currentThread().threadId();
                    long blocked = bean.getThreadInfo(id).getBlockedTime();
                    long cpu = bean.getCurrentThreadCpuTime();
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("并发探针启动超时");
                    }
                    for (int i = 0; i < queries; i++) {
                        long before = System.nanoTime();
                        // 各线程使用不同词条，排除互相命中缓存造成的吞吐量虚高。
                        query(path, term(first + i) + (fuzzy ? "x" : ""), fuzzy);
                        latency[first + i] = System.nanoTime() - before;
                    }
                    return new long[]{
                            bean.getThreadInfo(id).getBlockedTime() - blocked,
                            bean.getCurrentThreadCpuTime() - cpu};
                }));
            }
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("并发探针工作线程未就绪");
            }
            started = System.nanoTime();
            start.countDown();
            long blocked = 0;
            long cpu = 0;
            for (var future : pending) {
                long[] values = future.get(60, TimeUnit.SECONDS);
                blocked += values[0];
                cpu += values[1];
            }
            double seconds = (System.nanoTime() - started) / 1_000_000_000d;
            Arrays.sort(latency);
            System.out.printf(java.util.Locale.ROOT,
                    "search_load fuzzy=%s threads=%d queries=%d qps=%.1f p50_ms=%.3f p95_ms=%.3f p99_ms=%.3f blocked_thread_ms=%d cpu_per_query_us=%.3f cache_hits=%d cache_misses=%d%n",
                    fuzzy, threads, latency.length, latency.length / seconds,
                    latency[latency.length / 2] / 1_000_000d,
                    latency[(int) (latency.length * 0.95)] / 1_000_000d,
                    latency[(int) (latency.length * 0.99)] / 1_000_000d,
                    blocked, cpu / (1000d * latency.length),
                    SearchSession.debugCacheHits(), SearchSession.debugCacheMisses());
        }
    }

    private static String term(int id) {
        StringBuilder word = new StringBuilder("song");
        do {
            word.append((char) ('a' + id % 26));
            id /= 26;
        } while (id > 0);
        return word.toString();
    }
}
