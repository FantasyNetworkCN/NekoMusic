package com.neko.music.service;

import java.util.Arrays;

/** 可追加的紧凑 long 列表，供声纹倒排索引构建使用。 */
final class LongList {
    private long[] values;
    private int size;

    LongList(long[] initial) {
        if (initial == null || initial.length == 0) {
            values = new long[4];
        } else {
            values = Arrays.copyOf(initial, Math.max(4, initial.length + 4));
            size = initial.length;
        }
    }

    void add(long value) {
        if (size == values.length) {
            values = Arrays.copyOf(values, values.length * 2);
        }
        values[size++] = value;
    }

    int size() {
        return size;
    }

    long[] toArray() {
        return Arrays.copyOf(values, size);
    }
}
