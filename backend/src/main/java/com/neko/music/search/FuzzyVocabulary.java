/*
 * NekoMusic
 * Copyright (C) 2026 NekoMusic contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Portions derived from ArchoeraMusic, Copyright (C) 2026 ArchoeraMusic contributors,
 * AGPL-3.0-or-later. 修改说明：距离上限固定为 1，改为无分配的首差异与后缀检查。
 */

package com.neko.music.search;

import java.util.List;

/**
 * 模糊层的词典工具：在前缀有序词表里定位区间、判定编辑距离。
 *
 * <p>词典来源是索引自身的 FTS 词表（{@code fts5vocab}），查询侧按序缓存，
 * 变体只保留「索引里确实存在」的候选。
 */
final class FuzzyVocabulary {

    private FuzzyVocabulary() {
    }

    /** 半开区间 [start, end) 与 [end, …) 的便捷载体。 */
    record Range(int start, int end) {
        boolean isEmpty() {
            return start >= end;
        }
    }

    /** 在字典序有序词表中取「所有以 {@code prefix} 开头」的半开区间。 */
    static Range prefixRange(List<String> sorted, String prefix) {
        int start = lowerBound(sorted, prefix);
        if (start >= sorted.size()) {
            return new Range(start, start);
        }
        int low = start;
        int high = sorted.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (sorted.get(mid).startsWith(prefix)) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return new Range(start, low);
    }

    /** 编辑距离 ≤ 1 或单次相邻换位；保持原先按 UTF-16 字符比较的语义。 */
    static boolean withinOne(String left, String right) {
        int difference = left.length() - right.length();
        if (Math.abs(difference) > 1) {
            return false;
        }
        int common = Math.min(left.length(), right.length());
        int start = 0;
        while (start < common && left.charAt(start) == right.charAt(start)) {
            start++;
        }
        if (start == common) {
            return true;
        }
        if (difference > 0) {
            return left.regionMatches(start + 1, right, start, common - start);
        }
        if (difference < 0) {
            return left.regionMatches(start, right, start + 1, common - start);
        }
        if (left.regionMatches(start + 1, right, start + 1, common - start - 1)) {
            return true;
        }
        return start + 1 < common
                && left.charAt(start) == right.charAt(start + 1)
                && left.charAt(start + 1) == right.charAt(start)
                && left.regionMatches(start + 2, right, start + 2, common - start - 2);
    }

    private static int lowerBound(List<String> sorted, String key) {
        int low = 0;
        int high = sorted.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (sorted.get(mid).compareTo(key) < 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }
}
