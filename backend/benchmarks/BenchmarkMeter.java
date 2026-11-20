package com.neko.music.benchmark;

import com.sun.management.ThreadMXBean;

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Locale;

/** 离线性能探针；不随生产代码打包。 */
public final class BenchmarkMeter {
    private static Instrumentation instrumentation;
    private static final ThreadMXBean THREADS =
            (ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void premain(String arguments, Instrumentation agent) {
        instrumentation = agent;
    }

    public static void measure(String name, int units, int warmups, int samples, Runnable operation) {
        for (int i = 0; i < warmups; i++) {
            operation.run();
        }
        long id = Thread.currentThread().threadId();
        double[] elapsed = new double[samples];
        long totalCpu = 0;
        long totalAllocated = 0;
        for (int i = 0; i < samples; i++) {
            long allocated = THREADS.getThreadAllocatedBytes(id);
            long cpu = THREADS.getCurrentThreadCpuTime();
            long start = System.nanoTime();
            operation.run();
            elapsed[i] = (System.nanoTime() - start) / (1000d * units);
            totalCpu += THREADS.getCurrentThreadCpuTime() - cpu;
            totalAllocated += THREADS.getThreadAllocatedBytes(id) - allocated;
        }
        Arrays.sort(elapsed);
        System.out.printf(Locale.ROOT,
                "%s wall_median_us=%.3f wall_p95_us=%.3f cpu_mean_us=%.3f allocated_bytes=%.1f%n",
                name, elapsed[samples / 2], elapsed[(int) Math.ceil(samples * 0.95) - 1],
                totalCpu / (1000d * samples * units), totalAllocated / (double) (samples * units));
    }

    /** 统计索引可达对象的实际大小，包含键、哈希桶和 posting 数组；不估算 RSS。 */
    public static long retainedBytes(Object root) throws ReflectiveOperationException {
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        ArrayDeque<Object> pending = new ArrayDeque<>();
        pending.add(root);
        long bytes = 0;
        while (!pending.isEmpty()) {
            Object value = pending.removeLast();
            if (visited.put(value, Boolean.TRUE) != null) {
                continue;
            }
            bytes += instrumentation.getObjectSize(value);
            Class<?> type = value.getClass();
            if (type.isArray()) {
                if (!type.getComponentType().isPrimitive()) {
                    for (int i = 0; i < Array.getLength(value); i++) {
                        Object child = Array.get(value, i);
                        if (child != null) {
                            pending.add(child);
                        }
                    }
                }
                continue;
            }
            for (Class<?> parent = type; parent != null; parent = parent.getSuperclass()) {
                for (var field : parent.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) {
                        field.setAccessible(true);
                        Object child = field.get(value);
                        if (child != null) {
                            pending.add(child);
                        }
                    }
                }
            }
        }
        return bytes;
    }
}
