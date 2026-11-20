package com.neko.music.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WeightedLoadingCacheTest {
    @Test
    void evictsOnlyColdEntriesAndHonorsBothBudgets() {
        WeightedLoadingCache<String, String> cache = new WeightedLoadingCache<>(3, 9, String::length);
        cache.get("a", value -> true, () -> "aaa");
        cache.get("b", value -> true, () -> "bbb");
        cache.get("c", value -> true, () -> "ccc");
        assertEquals("aaa", cache.get("a", value -> true, () -> fail("热点不应重新加载")));
        cache.get("d", value -> true, () -> "ddd");
        assertEquals("ccc", cache.get("c", value -> true, () -> fail("不应整批清空")));
        assertEquals("new", cache.get("b", value -> true, () -> "new"));
        assertEquals(3, cache.size());
        assertEquals(9, cache.weight());

        WeightedLoadingCache<Integer, String> countBound = new WeightedLoadingCache<>(2, 100, String::length);
        for (int i = 0; i < 10; i++) {
            countBound.get(i, value -> true, () -> "");
        }
        assertEquals(2, countBound.size(), "零权重条目也必须受数量限制");
    }

    @Test
    void expiredOversizedAndFailedValuesCanBeRetried() {
        WeightedLoadingCache<String, String> cache = new WeightedLoadingCache<>(2, 4, String::length);
        cache.get("key", value -> true, () -> "old");
        assertEquals("new", cache.get("key", value -> !value.equals("old"), () -> "new"));
        assertEquals(3, cache.weight());
        cache.get("key", value -> false, () -> "oversized");
        assertEquals(0, cache.size());
        assertEquals(0, cache.weight());
        assertNull(cache.get("key", value -> true, () -> null));
        assertThrows(IllegalStateException.class,
                () -> cache.get("key", value -> true, () -> { throw new IllegalStateException(); }));
        assertEquals("ok", cache.get("key", value -> true, () -> "ok"));
    }

    @Test
    void concurrentMissesLoadOnce() throws Exception {
        WeightedLoadingCache<Integer, String> cache = new WeightedLoadingCache<>(2, 100, String::length);
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                futures.add(executor.submit(() -> cache.get(1, value -> true, () -> {
                    loads.incrementAndGet();
                    started.countDown();
                    await(release);
                    return "cover";
                })));
            }
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            for (var future : futures) {
                assertEquals("cover", future.get(5, TimeUnit.SECONDS));
            }
        }
        assertEquals(1, loads.get());
    }

    @Test
    void invalidationPreventsInFlightOldValueFromRepopulatingCache() throws Exception {
        WeightedLoadingCache<Integer, String> cache = new WeightedLoadingCache<>(2, 100, String::length);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var old = executor.submit(() -> cache.get(1, value -> true, () -> {
                started.countDown();
                await(release);
                return "old";
            }));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                cache.invalidate(1);
            } finally {
                release.countDown();
            }
            assertEquals("old", old.get(5, TimeUnit.SECONDS));
        }
        assertEquals("new", cache.get(1, value -> true, () -> "new"));
        cache.invalidateIf(key -> key == 1);
        assertEquals(0, cache.size());
        assertEquals(0, cache.weight());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("等待加载测试超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
