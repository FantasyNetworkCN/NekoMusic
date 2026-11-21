package com.neko.music.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SearchSessionConcurrencyTest {
    @TempDir
    Path directory;

    @AfterEach
    void closeSessions() {
        SearchSession.closeCached();
    }

    @Test
    void concurrentQueriesKeepExactResultsAndOneBoundedSharedCache() throws Exception {
        Path path = directory.resolve("search.db");
        build(path, 1);
        List<List<SearchSession.SearchHit>> expected = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            expected.add(run(path, "common " + word(i), false).items());
        }
        SearchSession.closeCached();
        try (var executor = Executors.newFixedThreadPool(16)) {
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < 16; worker++) {
                int number = worker;
                futures.add(executor.submit(() -> {
                    await(start);
                    for (int i = 0; i < 100; i++) {
                        int item = (i + number * 7) % 100;
                        assertEquals(expected.get(item), run(path, "common " + word(item), false).items());
                        run(path, "missing" + word(item), false);
                    }
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }
        assertTrue(SearchSession.debugCacheCount() <= SearchSession.CACHE_CAPACITY);
        var cached = SearchSession.class.getDeclaredField("cached");
        cached.setAccessible(true);
        Object pool = cached.get(null);
        var sessions = pool.getClass().getDeclaredField("sessions");
        sessions.setAccessible(true);
        int count = ((List<?>) sessions.get(pool)).size();
        assertTrue(count >= 1 && count <= 4, "读连接数必须有界");
    }

    @Test
    void externalCommitsInvalidateResultsAndSharedFuzzyVocabulary() throws Exception {
        Path path = directory.resolve("search.db");
        build(path, 1);
        assertEquals(List.of(), run(path, "nebulaax", true).items());
        try (FtsStore writer = new FtsStore()) {
            writer.open(path.toString(), false);
            writer.put(200, doc(200, "nebulaa"));
            writer.commit();
        }
        assertEquals(List.of("200"), ids(run(path, "nebulaax", true)));
        try (FtsStore writer = new FtsStore()) {
            writer.open(path.toString(), false);
            writer.delete(200);
            writer.commit();
        }
        assertEquals(List.of(), run(path, "nebulaax", true).items());
    }

    @Test
    void fullReplacementWaitsForReadersAndNeverMixesGenerations() throws Exception {
        Path path = directory.resolve("search.db");
        build(path, 1);
        try (var executor = Executors.newFixedThreadPool(9)) {
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                futures.add(executor.submit(() -> {
                    await(start);
                    for (int i = 0; i < 100; i++) {
                        List<String> ids = ids(run(path, "common", false));
                        assertEquals(100, ids.size());
                        int generation = Integer.parseInt(ids.getFirst()) / 1000;
                        assertTrue(generation >= 1 && generation <= 8);
                        assertTrue(ids.stream().allMatch(id -> Integer.parseInt(id) / 1000 == generation),
                                "一条查询不能混入不同代次");
                    }
                }));
            }
            futures.add(executor.submit(() -> {
                await(start);
                try {
                    for (int generation = 2; generation <= 8; generation++) {
                        build(path, generation);
                    }
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }));
            start.countDown();
            for (var future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }
        assertTrue(ids(run(path, "common", false)).stream().allMatch(id -> id.startsWith("8")));
    }

    @Test
    void switchingPathsDoesNotReuseAnotherIndexResults() throws Exception {
        Path first = directory.resolve("first.db");
        Path second = directory.resolve("second.db");
        build(first, 1);
        build(second, 2);
        for (int i = 0; i < 5; i++) {
            assertTrue(ids(run(first, "common", false)).stream().allMatch(id -> id.startsWith("1")));
            assertTrue(ids(run(second, "common", false)).stream().allMatch(id -> id.startsWith("2")));
        }
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                Path path = worker % 2 == 0 ? first : second;
                String prefix = worker % 2 == 0 ? "1" : "2";
                futures.add(executor.submit(() -> {
                    for (int i = 0; i < 10; i++) {
                        List<String> found = ids(run(path, "common", false));
                        assertEquals(100, found.size());
                        assertTrue(found.stream().allMatch(id -> id.startsWith(prefix)));
                    }
                }));
            }
            for (var future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void multilingualFallbacksRemainEquivalentAcrossReaders() throws Exception {
        Path path = directory.resolve("multilingual.db");
        try (FtsStore store = new FtsStore()) {
            store.open(path.toString(), true);
            String[] titles = {"晴天", "夏の夢", "가나다", "𪛖a", "nebulaa soft moon"};
            for (int i = 0; i < titles.length; i++) {
                FtsStore.DocTokens doc = doc(i + 1, "");
                var tokens = Analyzer.analyze(titles[i], true);
                doc.titleTok = tokens.tokens();
                doc.pinyinMetaTok = tokens.pinyin();
                doc.initialsMetaTok = tokens.pinyinInitials();
                doc.readingMetaTok = tokens.readings();
                doc.readingText = tokens.readings();
                store.put(i + 1, doc);
            }
            store.writeMeta(new FtsStore.StoreMeta());
            SearchSession.publishFullIndex(store);
        }
        List<String> queries = List.of("晴天", "qing tian", "夏の夢", "가나다", "𪛖a",
                "nebulaax", "soft moon", "晴天1");
        List<List<SearchSession.SearchHit>> expected = queries.stream()
                .map(query -> run(path, query, true).items()).toList();
        assertEquals(List.of("1"), expected.getFirst().stream().map(SearchSession.SearchHit::trackId).toList());
        SearchSession.closeCached();
        try (var executor = Executors.newFixedThreadPool(16)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < 16; worker++) {
                futures.add(executor.submit(() -> {
                    for (int round = 0; round < 5; round++) {
                        for (int i = 0; i < queries.size(); i++) {
                            assertEquals(expected.get(i), run(path, queries.get(i), true).items());
                        }
                    }
                }));
            }
            for (var future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void invalidationDuringQueryPreventsOldCacheRepopulation() throws Exception {
        Path path = directory.resolve("in-flight.db");
        build(path, 1);
        run(path, "common", false);
        var cachedField = SearchSession.class.getDeclaredField("cached");
        cachedField.setAccessible(true);
        Object pool = cachedField.get(null);
        var sessionsField = pool.getClass().getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        SearchSession session = (SearchSession) ((List<?>) sessionsField.get(pool)).getFirst();
        var storeField = SearchSession.class.getDeclaredField("store");
        storeField.setAccessible(true);
        FtsStore store = (FtsStore) storeField.get(session);
        var connectionField = FtsStore.class.getDeclaredField("connection");
        connectionField.setAccessible(true);
        Connection connection = (Connection) connectionField.get(store);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Connection proxy = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (p, method, args) -> {
                    try {
                        Object value = method.invoke(connection, args);
                        if (value instanceof PreparedStatement statement
                                && method.getName().equals("prepareStatement")
                                && ((String) args[0]).contains("FROM search_fts")) {
                            return Proxy.newProxyInstance(getClass().getClassLoader(),
                                    new Class<?>[]{PreparedStatement.class}, (ignored, m, a) -> {
                                        try {
                                            Object result = m.invoke(statement, a);
                                            if (m.getName().equals("executeQuery")) {
                                                started.countDown();
                                                await(release);
                                            }
                                            return result;
                                        } catch (InvocationTargetException e) {
                                            throw e.getCause();
                                        }
                                    });
                        }
                        return value;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        connectionField.set(store, proxy);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> run(path, "common songaa", false));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                SearchSession.invalidateQueryCache();
            } finally {
                release.countDown();
            }
            assertEquals(List.of("1000"), ids(future.get(5, TimeUnit.SECONDS)));
            assertEquals(0, SearchSession.debugCacheCount(), "失效前已开始的查询不能回填旧缓存");
        }
    }

    private static SearchSession.SearchResult run(Path path, String query, boolean fuzzy) {
        return SearchSession.run(path.toString(),
                new SearchSession.SearchRequest(query, SearchScope.METADATA, 200, 0, fuzzy, 1, 5000));
    }

    private static List<String> ids(SearchSession.SearchResult result) {
        return result.items().stream().map(SearchSession.SearchHit::trackId).toList();
    }

    private static void build(Path path, int generation) throws Exception {
        try (FtsStore store = new FtsStore()) {
            store.open(path.toString(), true);
            for (int i = 0; i < 100; i++) {
                int id = generation * 1000 + i;
                store.put(id, doc(id, "common " + word(i)));
            }
            FtsStore.StoreMeta meta = new FtsStore.StoreMeta();
            meta.hasHan = false;
            meta.hasJapanese = false;
            meta.hasKorean = false;
            store.writeMeta(meta);
            SearchSession.publishFullIndex(store);
        }
    }

    private static FtsStore.DocTokens doc(int id, String title) {
        FtsStore.DocTokens doc = new FtsStore.DocTokens();
        doc.trackId = Integer.toString(id);
        doc.titleTok = title;
        return doc;
    }

    private static String word(int number) {
        return "song" + (char) ('a' + number / 26) + (char) ('a' + number % 26);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("并发测试启动超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
