package com.neko.music.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FingerprintPostingsTest {
    @Test
    void preservesAll64KeyBitsAndZeroAcrossLookupAndIteration() {
        Map<Long, long[]> original = new HashMap<>();
        for (long key : new long[]{0, -1, Long.MIN_VALUE, Long.MAX_VALUE, 1L << 56, 2L << 56}) {
            original.put(key, new long[]{key, key + 1});
        }
        Random random = new Random(20261120);
        for (int i = 0; i < 10_000; i++) {
            original.put(random.nextLong(), new long[]{i});
        }
        FingerprintPostings compact = FingerprintPostings.copyOf(original);
        assertEquals(original.size(), compact.size());
        assertEquals(original, compact);
        assertEquals(original.keySet(), compact.keySet());
        assertEquals(original.values().stream().mapToLong(values -> values.length).sum(),
                compact.postingCount());
        original.forEach((key, value) -> assertSame(value, compact.get(key.longValue())));
        assertNull(compact.get(123456789L));
        assertNull(compact.get("0"));
        assertNull(compact.get(null));
        assertThrows(UnsupportedOperationException.class, () -> compact.put(9L, new long[]{1}));
        assertThrows(UnsupportedOperationException.class,
                () -> compact.entrySet().iterator().next().setValue(new long[]{1}));
    }

    @Test
    void incrementalLayersOverrideAndShareUnchangedArrays() {
        long[] shared = {100, 200};
        FingerprintPostings base = FingerprintPostings.copyOf(Map.of(1L, shared, 2L, new long[]{300}));
        FingerprintPostings first = new CompositePostings(base,
                Map.of(2L, new long[]{400, 500}, 3L, new long[]{600}));
        FingerprintPostings second = new CompositePostings(first,
                Map.of(3L, new long[]{700, 800, 900}, 4L, new long[]{1000}));
        assertEquals(4, second.size());
        assertEquals(8, second.postingCount());
        assertSame(shared, second.get(1L));
        assertArrayEquals(new long[]{400, 500}, second.get(2L));
        assertArrayEquals(new long[]{700, 800, 900}, second.get(3L));
        assertArrayEquals(new long[]{300}, base.get(2L));
        assertEquals(4, second.entrySet().stream().count());
        assertEquals(8, second.values().stream().mapToLong(values -> values.length).sum());
    }
}
