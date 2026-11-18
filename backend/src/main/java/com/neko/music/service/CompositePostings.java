package com.neko.music.service;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 两层不可变 posting 表：{@code overrides} 覆盖 {@code base} 中同 key 的条目，
 * 其余条目继续引用 {@code base} 里的数组。
 *
 * <p>增量构建通常只改动少量 hash。如果先把旧表复制进新的 {@code HashMap}，
 * 会额外申请数千万条记录，并让 {@code Long} 键在哈希桶里退化成大量
 * {@code HashMap$TreeNode}；这里只增加一层覆盖表，旧索引的数组原地复用。</p>
 */
final class CompositePostings extends AbstractMap<Long, long[]> {
    private final Map<Long, long[]> base;
    private final Map<Long, long[]> overrides;

    CompositePostings(Map<Long, long[]> base, Map<Long, long[]> overrides) {
        this.base = base;
        this.overrides = overrides;
    }

    @Override
    public long[] get(Object key) {
        long[] value = overrides.get(key);
        return value != null ? value : base.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return overrides.containsKey(key) || base.containsKey(key);
    }

    @Override
    public int size() {
        int extra = 0;
        for (Long key : overrides.keySet()) {
            if (!base.containsKey(key)) {
                extra++;
            }
        }
        return base.size() + extra;
    }

    /** 先遍历旧表（同 key 的数组换成覆盖值），再补上只存在于覆盖表的条目。 */
    @Override
    public Set<Entry<Long, long[]>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public Iterator<Entry<Long, long[]>> iterator() {
                return new Iterator<>() {
                    private final Iterator<Entry<Long, long[]>> baseIterator =
                            base.entrySet().iterator();
                    private final Iterator<Entry<Long, long[]>> overrideIterator =
                            overrides.entrySet().iterator();
                    private Entry<Long, long[]> pendingOverride;
                    private boolean overrideFetched;

                    private boolean fetchOverride() {
                        if (overrideFetched) {
                            return pendingOverride != null;
                        }
                        overrideFetched = true;
                        while (overrideIterator.hasNext()) {
                            Entry<Long, long[]> candidate = overrideIterator.next();
                            if (!base.containsKey(candidate.getKey())) {
                                pendingOverride = candidate;
                                return true;
                            }
                        }
                        pendingOverride = null;
                        return false;
                    }

                    @Override
                    public boolean hasNext() {
                        return baseIterator.hasNext() || fetchOverride();
                    }

                    @Override
                    public Entry<Long, long[]> next() {
                        if (baseIterator.hasNext()) {
                            Entry<Long, long[]> entry = baseIterator.next();
                            long[] overridden = overrides.get(entry.getKey());
                            return overridden == null
                                    ? entry
                                    : new SimpleImmutableEntry<>(entry.getKey(), overridden);
                        }
                        if (fetchOverride()) {
                            Entry<Long, long[]> value = pendingOverride;
                            pendingOverride = null;
                            overrideFetched = false;
                            return value;
                        }
                        throw new NoSuchElementException();
                    }
                };
            }

            @Override
            public int size() {
                return CompositePostings.this.size();
            }
        };
    }
}
