/*
 * NekoMusic
 * Copyright (C) 2026 NekoMusic contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Portions derived from ArchoeraMusic, Copyright (C) 2026 Archoera && BetaStudio2,
 * AGPL-3.0-or-later. 修改说明：繁转简复用 Neko 既有的 opencc4j，
 * 拼音改为离线导出词库的最长匹配（详见 PinyinFacade）。
 */

package com.neko.music.search;

import com.neko.music.util.ChineseConverter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 本地曲库搜索分析器（索引侧 / 查询侧同构）。
 *
 * <p>链：逐码点 NFKC → 去音符号 → 异体字补齐 → 小写 → 繁转简 → 脚本切分 → 分词
 * （CJK/假名/谚文 bigram+unigram，拉丁/数字整词）→ 派生列（全拼 / 首字母 / 读音）。
 *
 * <p>同步、无 IO、无可变静态状态（除惰性数据表），可安全并发调用。
 */
public final class Analyzer {

    /**
     * 分析器语义版本；任何影响词元输出的变更都必须递增（触发全量重建）。
     * 采用 analyzer v13：CJK 全平面按码点切分与索引。
     */
    public static final int ANALYZER_VERSION = 13;

    /** 查询侧跨语言候选合并去重后的总预算。 */
    public static final int CROSS_LINGUAL_BUDGET = 32;

    private Analyzer() {
    }

    /** 可检索字段（枚举序号是索引列序契约，不得变更）。 */
    public enum Field {
        TITLE,
        ARTIST,
        ALBUM,
        LYRICS_ORIGINAL,
        LYRICS_TRANSLATION,
        LYRICS_ROMAJI,
        FILENAME
    }

    /** 索引侧分析结果（词元 / 全拼 / 首字母 / 读音，均为空格分隔串）。 */
    public record AnalysisResult(String tokens, String pinyin, String pinyinInitials, String readings) {

        public boolean hasTokens() {
            return tokens != null && !tokens.isEmpty();
        }
    }

    /** 查询侧结构化词元。 */
    public static final class QueryTokens {
        private final List<String> lexical = new ArrayList<>();
        private final List<String> pinyin = new ArrayList<>();
        private final List<String> crossLingual = new ArrayList<>();
        private String pinyinInitials;
        private boolean hasCjk;
        private boolean pinyinComplete;
        private boolean hasNonIndexedLetterOrDigit;

        public List<String> lexical() {
            return lexical;
        }

        public List<String> pinyin() {
            return pinyin;
        }

        /** 首字母串；文本不含 CJK 时为 {@code null}。 */
        public String pinyinInitials() {
            return pinyinInitials;
        }

        public List<String> crossLingual() {
            return crossLingual;
        }

        /** 查询是否包含 CJK；用于判断拼音兜底是否能代表完整查询。 */
        public boolean hasCjk() {
            return hasCjk;
        }

        /** CJK 每个码点都成功产出拼音音节时为 true；含无读音扩展汉字时为 false。 */
        public boolean pinyinComplete() {
            return pinyinComplete;
        }

        /** 查询是否含不属于 CJK / 假名 / 谚文索引脚本的字母或数字（如拉丁字母、阿拉伯数字）。 */
        public boolean hasNonIndexedLetterOrDigit() {
            return hasNonIndexedLetterOrDigit;
        }
    }

    private enum ScriptKind {
        NONE,
        CJK,
        /** 需要子串索引的脚本（假名 / 谚文）：bigram + unigram，但不进拼音 / 首字母列。 */
        SUBSTRING,
        LATIN,
        DIGIT
    }

    private record Segment(ScriptKind kind, String text) {
    }

    /** 汉字判定，供索引 / 查询 / 歌词层共用。 */
    public static boolean isCjk(char c) {
        return ScriptRanges.isCjk(c);
    }

    /** 汉字判定；扩展 B 及之后必须使用该入口。 */
    public static boolean isCjk(int codePoint) {
        return ScriptRanges.isCjk(codePoint);
    }

    /**
     * 索引侧分析。
     *
     * @param text            原文
     * @param includeReadings 调用方判定整首曲目是日文时，纯汉字段也查读音表
     */
    public static AnalysisResult analyze(String text, boolean includeReadings) {
        Parts parts = analyzeCore(text);
        return new AnalysisResult(
                String.join(" ", parts.tokens),
                String.join(" ", parts.pinyin),
                String.join(" ", parts.initials),
                JapaneseReadings.readingBigrams(text, includeReadings));
    }

    /** 查询侧分析：与索引侧同构，返回结构化词元列表。 */
    public static QueryTokens analyzeQuery(String text) {
        Parts parts = analyzeCore(text);
        QueryTokens query = new QueryTokens();
        query.lexical().addAll(parts.tokens);

        List<String> buffer = new ArrayList<>();
        for (String token : parts.pinyin) {
            if (PinyinSegmenter.trySegment(token, buffer)) {
                query.pinyin().addAll(buffer);
            } else {
                query.pinyin().add(token);
            }
        }

        query.pinyinInitials = parts.hasCjk ? String.join(" ", parts.initials) : null;
        query.hasCjk = parts.hasCjk;
        query.pinyinComplete = !parts.hasCjk
                || parts.cjkPinyinSyllables == parts.cjkCodePoints;
        query.hasNonIndexedLetterOrDigit = parts.hasNonIndexedLetterOrDigit;
        query.crossLingual().addAll(collectCrossLingual(text));
        removePinyinCollisions(query);
        return query;
    }

    /**
     * 去噪：Unihan 释义里的罗马化专名（如 周 → “Zhou dynasty” 的 zhou）会与查询自身拼音音节同形，
     * 该串已由拼音分支覆盖，保留只会引入重复噪声命中。
     */
    private static void removePinyinCollisions(QueryTokens query) {
        if (query.crossLingual().isEmpty() || query.pinyin().isEmpty()) {
            return;
        }
        Set<String> pinyin = new HashSet<>(query.pinyin());
        query.crossLingual().removeIf(pinyin::contains);
    }

    /** 查询侧跨语言候选（Unihan 释义扩展；不改索引侧）。 */
    private static List<String> collectCrossLingual(String text) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (text == null || text.isEmpty()) {
            return result;
        }

        for (Segment segment : splitSegments(normalize(text))) {
            if (segment.kind() == ScriptKind.CJK) {
                String textSegment = segment.text();
                for (int i = 0; i < textSegment.length(); ) {
                    int cp = textSegment.codePointAt(i);
                    List<String> words = HanziData.glossWords(cp);
                    if (words == null) {
                        i += Character.charCount(cp);
                        continue;
                    }
                    for (String word : words) {
                        if (result.size() >= CROSS_LINGUAL_BUDGET) {
                            return result;
                        }
                        if (!word.isEmpty() && seen.add(word)) {
                            result.add(word);
                        }
                    }
                    i += Character.charCount(cp);
                }
            } else {
                List<Integer> codes = HanziData.glossHanzi(segment.text());
                if (codes == null) {
                    continue;
                }
                for (int cp : codes) {
                    if (result.size() >= CROSS_LINGUAL_BUDGET) {
                        return result;
                    }
                    if (cp >= 0 && cp <= 0x10FFFF && (cp < 0xD800 || cp > 0xDFFF)) {
                        String term = new String(Character.toChars(cp));
                        if (seen.add(term)) {
                            result.add(term);
                        }
                    }
                }
            }
        }
        return result;
    }

    private static final class Parts {
        final List<String> tokens = new ArrayList<>();
        final List<String> pinyin = new ArrayList<>();
        final List<String> initials = new ArrayList<>();
        boolean hasCjk;
        int cjkCodePoints;
        int cjkPinyinSyllables;
        boolean hasNonIndexedLetterOrDigit;
    }

    private static Parts analyzeCore(String text) {
        Parts parts = new Parts();
        if (text == null || text.isEmpty()) {
            return parts;
        }

        for (Segment segment : splitSegments(normalize(text))) {
            switch (segment.kind()) {
                case CJK -> {
                    parts.hasCjk = true;
                    addCjkSegment(segment.text(), parts);
                }
                case SUBSTRING -> addSubstringSegment(segment.text(), parts);
                default -> {
                    parts.hasNonIndexedLetterOrDigit = true;
                    parts.tokens.add(segment.text());
                    parts.pinyin.add(segment.text());
                    parts.initials.add(segment.text());
                }
            }
        }
        return parts;
    }

    /** CJK 段：bigram 在前（短语连续）、unigram 在后；拼音整段词级消歧；首字母合并为一个 token。 */
    private static void addCjkSegment(String segment, Parts parts) {
        List<String> units = codePointUnits(segment);
        for (int i = 0; i + 1 < units.size(); i++) {
            parts.tokens.add(units.get(i) + units.get(i + 1));
        }
        for (String unit : units) {
            parts.tokens.add(unit);
        }

        List<String> pinyin = PinyinFacade.fromCjkRun(segment);
        parts.pinyin.addAll(pinyin);
        parts.cjkCodePoints += units.size();
        parts.cjkPinyinSyllables += pinyin.size();
        String initials = PinyinFacade.initials(segment);
        if (!initials.isEmpty()) {
            parts.initials.add(initials);
        }
    }

    /** 假名 / 谚文段：与 CJK 同构的 bigram + unigram，不产出拼音 / 首字母。 */
    private static void addSubstringSegment(String segment, Parts parts) {
        List<String> units = codePointUnits(segment);
        for (int i = 0; i + 1 < units.size(); i++) {
            parts.tokens.add(units.get(i) + units.get(i + 1));
        }
        for (String unit : units) {
            parts.tokens.add(unit);
        }
    }

    private static List<String> codePointUnits(String text) {
        List<String> units = new ArrayList<>();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            units.add(new String(Character.toChars(cp)));
            i += Character.charCount(cp);
        }
        return units;
    }

    /**
     * 逐码点 NFKC 兼容折叠 → 去音符号 / 丢弃组合音标 → 异体字补齐 → 小写 → 繁转简。
     * 标点与空白留待切分阶段丢弃。
     */
    static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }

        StringBuilder compat = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String replacement = HanziData.compatibilityFolded(cp);
            if (replacement != null) {
                compat.append(replacement);
            } else {
                compat.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }

        StringBuilder folded = new StringBuilder(compat.length());
        String compatText = compat.toString();
        for (int i = 0; i < compatText.length(); ) {
            int cp = compatText.codePointAt(i);
            if (!HanziData.isCombiningDiacritic(cp)) {
                String baseLetter = HanziData.accentFolded(cp);
                if (baseLetter != null) {
                    folded.append(baseLetter);
                } else {
                    String simplifiedVariant = HanziData.variantFolded(cp);
                    if (simplifiedVariant != null) {
                        folded.append(simplifiedVariant);
                    } else {
                        folded.appendCodePoint(cp);
                    }
                }
            }
            i += Character.charCount(cp);
        }

        return ChineseConverter.toSimplified(folded.toString().toLowerCase(Locale.ROOT));
    }

    /** 脚本切分：连续 CJK / 假名谚文 / 拉丁 / 数字各成段；其余（标点等）丢弃并断段。 */
    private static List<Segment> splitSegments(String text) {
        List<Segment> segments = new ArrayList<>();
        StringBuilder builder = new StringBuilder();
        ScriptKind kind = ScriptKind.NONE;

        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            ScriptKind current = classify(cp);
            if (current == ScriptKind.NONE) {
                if (builder.length() > 0) {
                    segments.add(new Segment(kind, builder.toString()));
                    builder.setLength(0);
                    kind = ScriptKind.NONE;
                }
                i += Character.charCount(cp);
                continue;
            }
            if (current != kind) {
                if (builder.length() > 0) {
                    segments.add(new Segment(kind, builder.toString()));
                    builder.setLength(0);
                }
            }
            kind = current;
            builder.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        if (builder.length() > 0) {
            segments.add(new Segment(kind, builder.toString()));
        }
        return segments;
    }

    private static ScriptKind classify(int codePoint) {
        if (ScriptRanges.isCjk(codePoint)) {
            return ScriptKind.CJK;
        }
        if (ScriptRanges.isSubstringScript(codePoint)) {
            return ScriptKind.SUBSTRING;
        }
        if (Character.isDigit(codePoint)) {
            return ScriptKind.DIGIT;
        }
        if (Character.isLetter(codePoint)) {
            return ScriptKind.LATIN;
        }
        return ScriptKind.NONE;
    }
}
