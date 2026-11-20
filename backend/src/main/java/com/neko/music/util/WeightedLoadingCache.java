package com.neko.music.util;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * 同时限制条目数和权重的进程内缓存，按访问顺序逐项淘汰。
 *
 * <p>加载在固定条带锁内合并，不持有缓存锁执行 IO。失效纪元阻止已经开始的旧加载在失效后
 * 重新填回缓存；失败或返回 null 不缓存，因此瞬时 IO / 数据库错误可在下次请求重试。</p>
 */
public final class WeightedLoadingCache<K, V> {
    private record Entry<V>(V value, long weight) {
    }

    private final int maxEntries;
    private final long maxWeight;
    private final ToLongFunction<? super V> weigher;
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final StripedLock loadingLocks = new StripedLock(64);
    private long weight;
    private long generation;

    public WeightedLoadingCache(int maxEntries, long maxWeight, ToLongFunction<? super V> weigher) {
        if (maxEntries <= 0 || maxWeight <= 0) {
            throw new IllegalArgumentException("缓存容量必须为正数");
        }
        this.maxEntries = maxEntries;
        this.maxWeight = maxWeight;
        this.weigher = java.util.Objects.requireNonNull(weigher);
    }

    /** 命中仍有效的条目，或合并同一 key 的加载；有效性由调用方的文件版本 / TTL 判断。 */
    public V get(K key, Predicate<? super V> fresh, Supplier<? extends V> loader) {
        V cached = find(key, fresh);
        if (cached != null) {
            return cached;
        }
        ReentrantLock lock = loadingLocks.forKey(key);
        lock.lock();
        try {
            cached = find(key, fresh);
            if (cached != null) {
                return cached;
            }
            long started;
            synchronized (this) {
                started = generation;
            }
            V loaded = loader.get();
            if (loaded != null) {
                long loadedWeight = Math.max(0, weigher.applyAsLong(loaded));
                synchronized (this) {
                    if (started == generation && loadedWeight <= maxWeight) {
                        put(key, loaded, loadedWeight);
                    }
                }
            }
            return loaded;
        } finally {
            lock.unlock();
        }
    }

    private synchronized V find(K key, Predicate<? super V> fresh) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (fresh.test(entry.value())) {
            return entry.value();
        }
        entries.remove(key);
        weight -= entry.weight();
        return null;
    }

    /** 调用时持有缓存锁；仅淘汰满足新预算所必需的最冷条目。 */
    private void put(K key, V value, long loadedWeight) {
        Entry<V> previous = entries.remove(key);
        if (previous != null) {
            weight -= previous.weight();
        }
        Iterator<Entry<V>> iterator = entries.values().iterator();
        while (iterator.hasNext()
                && (entries.size() >= maxEntries || weight > maxWeight - loadedWeight)) {
            weight -= iterator.next().weight();
            iterator.remove();
        }
        entries.put(key, new Entry<>(value, loadedWeight));
        weight += loadedWeight;
    }

    public synchronized void invalidate(K key) {
        generation++;
        Entry<V> removed = entries.remove(key);
        if (removed != null) {
            weight -= removed.weight();
        }
    }

    public synchronized void invalidateIf(Predicate<? super K> predicate) {
        generation++;
        Iterator<Map.Entry<K, Entry<V>>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<K, Entry<V>> entry = iterator.next();
            if (predicate.test(entry.getKey())) {
                weight -= entry.getValue().weight();
                iterator.remove();
            }
        }
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized long weight() {
        return weight;
    }
}
