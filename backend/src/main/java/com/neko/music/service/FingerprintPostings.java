package com.neko.music.service;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * 声纹只读倒排表：原始 long 键直接寻址，避免常驻 Long 对象及查询时的装箱。
 * 数组仍由索引独占，增量层复用未修改的数组；Map 视图只供持久化遍历。
 */
abstract class FingerprintPostings extends AbstractMap<Long, long[]> {
    private static final FingerprintPostings EMPTY = new Compact(Map.of());

    abstract long[] get(long hash);

    abstract long postingCount();

    static FingerprintPostings empty() {
        return EMPTY;
    }

    static FingerprintPostings copyOf(Map<Long, long[]> source) {
        if (source instanceof FingerprintPostings postings) {
            return postings;
        }
        return source.isEmpty() ? EMPTY : new Compact(source);
    }

    @Override
    public final long[] get(Object key) {
        return key instanceof Long hash ? get(hash.longValue()) : null;
    }

    @Override
    public final boolean containsKey(Object key) {
        return get(key) != null;
    }

    /** 混合全部 64 位，避免声纹高位相近、低位稀疏时聚集到少数桶。 */
    static int bucket(long hash) {
        hash = (hash ^ (hash >>> 33)) * 0xff51afd7ed558ccdL;
        hash = (hash ^ (hash >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return (int) (hash ^ (hash >>> 33));
    }

    private static final class Compact extends FingerprintPostings {
        private final long[] hashes;
        private final long[][] values;
        private final int size;
        private final long postingCount;

        private Compact(Map<Long, long[]> source) {
            size = source.size();
            int capacity = 1;
            // 负载率不超过 3/4，至少保留一个空桶以终止未命中查询。
            while (size > capacity * 3L / 4) {
                if (capacity >= 1 << 30) {
                    throw new IllegalArgumentException("声纹哈希条目过多");
                }
                capacity <<= 1;
            }
            hashes = new long[capacity];
            values = new long[capacity][];
            long count = 0;
            for (Entry<Long, long[]> entry : source.entrySet()) {
                long hash = Objects.requireNonNull(entry.getKey());
                long[] postings = Objects.requireNonNull(entry.getValue());
                if (postings.length == 0) {
                    throw new IllegalArgumentException("posting list cannot be empty");
                }
                int slot = bucket(hash) & (capacity - 1);
                while (values[slot] != null) {
                    slot = (slot + 1) & (capacity - 1);
                }
                hashes[slot] = hash;
                values[slot] = postings;
                count += postings.length;
            }
            postingCount = count;
        }

        @Override
        long[] get(long hash) {
            int mask = values.length - 1;
            int slot = bucket(hash) & mask;
            while (values[slot] != null) {
                if (hashes[slot] == hash) {
                    return values[slot];
                }
                slot = (slot + 1) & mask;
            }
            return null;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        long postingCount() {
            return postingCount;
        }

        @Override
        public Collection<long[]> values() {
            return new AbstractCollection<>() {
                @Override
                public Iterator<long[]> iterator() {
                    return new SlotIterator<>() {
                        @Override
                        long[] valueAt(int slot) {
                            return values[slot];
                        }
                    };
                }

                @Override
                public int size() {
                    return size;
                }
            };
        }

        @Override
        public Set<Entry<Long, long[]>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<Long, long[]>> iterator() {
                    return new SlotIterator<>() {
                        @Override
                        Entry<Long, long[]> valueAt(int slot) {
                            return new SimpleImmutableEntry<>(hashes[slot], values[slot]);
                        }
                    };
                }

                @Override
                public int size() {
                    return size;
                }
            };
        }

        private abstract class SlotIterator<T> implements Iterator<T> {
            private int next;

            @Override
            public boolean hasNext() {
                while (next < values.length && values[next] == null) {
                    next++;
                }
                return next < values.length;
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return valueAt(next++);
            }

            abstract T valueAt(int slot);
        }
    }
}
