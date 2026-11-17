/*
 * NekoMusic
 * Copyright (C) 2026 NekoMusic contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Portions derived from ArchoeraMusic, Copyright (C) 2026 Archoera && BetaStudio2,
 * AGPL-3.0-or-later. 修改说明：行为保持一致。
 */

package com.neko.music.search;

/**
 * 字符脚本分类（与索引器共用同一套判定）。
 *
 * <p>「哪些脚本要子串索引」与「什么才算拉丁（罗马音）」以这里为唯一事实来源，
 * 避免索引侧、查询侧与歌词分层各写一套导致漂移。
 */
public final class ScriptRanges {

    private ScriptRanges() {
    }

    /**
     * 汉字：覆盖基本区、扩展 A～I、兼容表意区及其补充平面。
     *
     * <p>搜索链必须按 Unicode code point 判断；扩展 B 及之后的汉字在 Java 中是代理对，
     * 任何按 {@code char} 判断的入口都会把它们误当成两个未知字符。
     */
    public static boolean isCjk(int codePoint) {
        return codePoint == 0x3007
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0x20000 && codePoint <= 0x2A6DF)
                || (codePoint >= 0x2A700 && codePoint <= 0x2B73F)
                || (codePoint >= 0x2B740 && codePoint <= 0x2B81F)
                || (codePoint >= 0x2B820 && codePoint <= 0x2CEAF)
                || (codePoint >= 0x2CEB0 && codePoint <= 0x2EBEF)
                || (codePoint >= 0x2EBF0 && codePoint <= 0x2EE5F)
                || (codePoint >= 0x2F800 && codePoint <= 0x2FA1F)
                || (codePoint >= 0x30000 && codePoint <= 0x3134F)
                || (codePoint >= 0x31350 && codePoint <= 0x323AF);
    }

    /** 兼容旧调用；代理项会自然返回 false。 */
    public static boolean isCjk(char c) {
        return isCjk((int) c);
    }

    /**
     * 需要子串索引的脚本：假名（平 / 片 / 半角 / 片假名音标扩展）+ 谚文（音节 / 字母 / 兼容字母）。
     *
     * <p>这些脚本与汉字同样以 bigram + unigram 入索引，但**不进拼音 / 首字母列**。
     */
    public static boolean isSubstringScript(int codePoint) {
        return isKana(codePoint) || isHangul(codePoint);
    }

    /** 兼容旧调用；代理项会自然返回 false。 */
    public static boolean isSubstringScript(char c) {
        return isSubstringScript((int) c);
    }

    /** 假名：平假名 / 片假名 / 音标扩展 / 半角片假名 / 补充平面假名。 */
    public static boolean isKana(int codePoint) {
        return (codePoint >= 0x3040 && codePoint <= 0x309F)
                || (codePoint >= 0x30A0 && codePoint <= 0x30FF)
                || (codePoint >= 0x31F0 && codePoint <= 0x31FF)
                || (codePoint >= 0xFF66 && codePoint <= 0xFF9D)
                || (codePoint >= 0x1AFF0 && codePoint <= 0x1AFFF)
                || (codePoint >= 0x1B000 && codePoint <= 0x1B16F);
    }

    public static boolean isKana(char c) {
        return isKana((int) c);
    }

    /** 谚文：音节 + 字母（Jamo）+ 兼容字母。 */
    public static boolean isHangul(int codePoint) {
        return (codePoint >= 0xAC00 && codePoint <= 0xD7AF)
                || (codePoint >= 0x1100 && codePoint <= 0x11FF)
                || (codePoint >= 0x3130 && codePoint <= 0x318F);
    }

    public static boolean isHangul(char c) {
        return isHangul((int) c);
    }

    /** 拉丁字母——用于「这一行是不是罗马音」的判定。 */
    public static boolean isLatinLetter(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN;
    }

    public static boolean isLatinLetter(char c) {
        return isLatinLetter((int) c);
    }

    /**
     * 是否为纯拉丁行（含拉丁字母、且不含汉字 / 假名 / 谚文）——译文层与音译层的保守判定。
     */
    public static boolean isLatinLine(String text) {
        if (text == null) {
            return false;
        }
        boolean hasLatin = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isCjk(cp) || isSubstringScript(cp)) {
                return false;
            }
            if (isLatinLetter(cp)) {
                hasLatin = true;
            }
            i += Character.charCount(cp);
        }
        return hasLatin;
    }
}
