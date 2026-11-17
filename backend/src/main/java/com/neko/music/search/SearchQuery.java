/*
 * NekoMusic
 * Copyright (C) 2026 NekoMusic contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Portions derived from ArchoeraMusic, Copyright (C) 2026 Archoera && BetaStudio2,
 * AGPL-3.0-or-later. 修改说明：保持表达式逐字符等价。
 */

package com.neko.music.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FTS5 {@code MATCH} 表达式编译（纯函数，无 IO，可单测）。
 *
 * <p>所有词元都必须经 {@link #quote(String)} 转义（内部 {@code "} 翻倍），
 * 多列过滤必须写成 {@code {col_a col_b} : expr}。
 */
final class SearchQuery {

    private SearchQuery() {
    }

    static final String METADATA_COLUMNS =
            "title_tok artist_tok album_tok filename_tok pinyin_meta_tok initials_meta_tok reading_meta_tok";
    static final String LYRICS_COLUMNS =
            "lyrics_tok lyrics_tr_tok lyrics_rom_tok pinyin_lyrics_tok initials_lyrics_tok";
    static final String PINYIN_COLUMNS = "pinyin_meta_tok pinyin_lyrics_tok";
    static final String INITIALS_COLUMNS = "initials_meta_tok initials_lyrics_tok";
    static final String READING_COLUMNS = "reading_meta_tok lyrics_rom_tok";

    static final int DEFAULT_MAX_FUZZY_VARIANTS = 500;
    static final int DEFAULT_MAX_CJK_VARIANTS = 200;
    static final int MAX_CJK_QUERY_LENGTH = 64;
    static final int MIN_PREFIX_TOKEN_LENGTH = 3;
    static final int MIN_INITIALS_PREFIX_LENGTH = 2;
    static final int MAX_PINYIN_VARIANTS_PER_SYLLABLE = 4;

    private static final Map<Character, String> SUBSTITUTION_ALTERNATIVES = new HashMap<>();

    static {
        put('a', "eiou");
        put('e', "aiou");
        put('i', "aeou");
        put('o', "aeiu");
        put('u', "aeio");
        put('b', "pv");
        put('p', "b");
        put('v', "bf");
        put('f', "v");
        put('d', "t");
        put('t', "d");
        put('c', "ksz");
        put('k', "csq");
        put('s', "cz");
        put('z', "s");
        put('g', "j");
        put('j', "g");
        put('m', "n");
        put('n', "m");
        put('l', "r");
        put('r', "l");
        put('q', "k");
        put('w', "v");
    }

    private static void put(char key, String value) {
        SUBSTITUTION_ALTERNATIVES.put(key, value);
    }

    /** 声母模糊音对（双向）：平翘舌、n/l、f/h。 */
    private static final String[][] PINYIN_INITIAL_FUZZ = {
            {"zh", "z"}, {"ch", "c"}, {"sh", "s"}, {"n", "l"}, {"f", "h"},
    };

    /** 韵母模糊音对（双向，仅匹配词尾）：前后鼻音。 */
    private static final String[][] PINYIN_FINAL_FUZZ = {
            {"ang", "an"}, {"eng", "en"}, {"ing", "in"},
    };

    private static final int MAX_EXACT_TERMS_PER_VARIANT = 32;

    /** 转义为 FTS5 双引号字符串（内部 {@code "} 翻倍）。 */
    static String quote(String token) {
        return "\"" + token.replace("\"", "\"\"") + "\"";
    }

    /** 编译主趟表达式；无可匹配内容返回 {@code null}。 */
    static String compileMatch(Analyzer.QueryTokens tokens, SearchScope scope, boolean includeCrossLingual) {
        List<String> branches = new ArrayList<>();
        add(branches, scopeExpression(lexicalBranch(tokens.lexical()), scope));
        add(branches, pinyinBranch(tokens, scope));
        add(branches, initialsBranch(tokens, scope));
        if (includeCrossLingual) {
            add(branches, scopeExpression(crossLingualBranch(tokens.crossLingual()), scope));
        }
        return joinBranches(branches);
    }

    /** 跨语言候选分支：候选逐字加引号后 OR。 */
    static String compileCrossLingual(Analyzer.QueryTokens tokens, SearchScope scope) {
        return scopeExpression(crossLingualBranch(tokens.crossLingual()), scope);
    }

    /**
     * 拼音宽松兜底支：音节位置无关 AND，末音节 ≥3 字符前缀；&lt;2 音节返回 {@code null}。
     */
    static String compilePinyinLoose(Analyzer.QueryTokens tokens, SearchScope scope) {
        // 该兜底只服务用户直接输入的拼音；中文查询再做无序拼音音节 AND 会把整句拆散。
        if (tokens.hasCjk()) {
            return null;
        }
        List<String> clean = clean(tokens.pinyin());
        if (clean.size() < 2) {
            return null;
        }
        if (clean.stream().anyMatch(token -> !PinyinSegmenter.isSyllable(token))) {
            return null;
        }

        List<String> chunks = new ArrayList<>(clean.size());
        for (int i = 0; i < clean.size(); i++) {
            String syllable = clean.get(i);
            boolean prefix = i == clean.size() - 1 && syllable.length() >= MIN_PREFIX_TOKEN_LENGTH;
            List<String> variants = pinyinFuzzyVariants(syllable, MAX_PINYIN_VARIANTS_PER_SYLLABLE);
            if (variants.size() == 1) {
                chunks.add(prefix ? quote(variants.get(0)) + "*" : quote(variants.get(0)));
                continue;
            }
            StringBuilder group = new StringBuilder();
            for (String variant : variants) {
                if (group.length() > 0) {
                    group.append(" OR ");
                }
                group.append(prefix ? quote(variant) + "*" : quote(variant));
            }
            chunks.add("(" + group + ")");
        }

        String body = String.join(" AND ", chunks);
        return scopedColumns(pinyinColumns(scope), body);
    }

    private static List<String> pinyinFuzzyVariants(String syllable, int maxVariants) {
        List<String> output = new ArrayList<>();
        output.add(syllable);
        if (maxVariants <= 1 || !PinyinSegmenter.isSyllable(syllable)) {
            return output;
        }

        String initial = swapAffix(syllable, PINYIN_INITIAL_FUZZ, true);
        String fin = swapAffix(syllable, PINYIN_FINAL_FUZZ, false);

        addVariant(output, initial, maxVariants);
        addVariant(output, fin, maxVariants);
        if (initial != null) {
            addVariant(output, swapAffix(initial, PINYIN_FINAL_FUZZ, false), maxVariants);
        }
        return output;
    }

    private static void addVariant(List<String> output, String candidate, int maxVariants) {
        if (candidate == null || candidate.isEmpty() || output.size() >= maxVariants) {
            return;
        }
        if (!output.contains(candidate)) {
            output.add(candidate);
        }
    }

    private static String swapAffix(String text, String[][] pairs, boolean prefix) {
        for (String[] pair : pairs) {
            String a = pair[0];
            String b = pair[1];
            if (prefix) {
                if (text.startsWith(a)) {
                    return b + text.substring(a.length());
                }
                if (text.startsWith(b)) {
                    return a + text.substring(b.length());
                }
            } else {
                if (text.endsWith(a)) {
                    return text.substring(0, text.length() - a.length()) + b;
                }
                if (text.endsWith(b)) {
                    return text.substring(0, text.length() - b.length()) + a;
                }
            }
        }
        return null;
    }

    /** 日文读音兜底支：拉丁 / 数字词元的字母 bigram 短语 AND 到读音列。 */
    static String compileReading(Analyzer.QueryTokens tokens, SearchScope scope) {
        List<String> parts = new ArrayList<>();
        for (String token : tokens.lexical()) {
            List<String[]> sequences = readingSequencesOf(token);
            if (sequences.isEmpty()) {
                continue;
            }
            List<String> phrases = new ArrayList<>(sequences.size());
            for (String[] sequence : sequences) {
                phrases.add(quote(String.join(" ", sequence)));
            }
            parts.add(phrases.size() == 1 ? phrases.get(0) : "(" + String.join(" OR ", phrases) + ")");
        }
        if (parts.isEmpty()) {
            return null;
        }
        String body = String.join(" AND ", parts);
        return scopedColumns(readingColumns(scope), body);
    }

    /** 查询中可走读音趟的词元的规范形 bigram 序列（供 ReadingRanker 分档）。 */
    static List<String[]> readingSequences(Analyzer.QueryTokens tokens) {
        List<String[]> output = new ArrayList<>();
        for (String token : tokens.lexical()) {
            addReadingSequences(token, output);
        }
        return output;
    }

    private static List<String[]> readingSequencesOf(String token) {
        List<String[]> output = new ArrayList<>();
        addReadingSequences(token, output);
        return output;
    }

    private static void addReadingSequences(String token, List<String[]> output) {
        String lower = token.toLowerCase(java.util.Locale.ROOT);
        if (lower.length() < 2 || hasIndexedScript(lower)) {
            return;
        }
        for (String variant : RomajiCanonical.variants(lower)) {
            if (variant.length() < 2) {
                continue;
            }
            String[] bigrams = new String[variant.length() - 1];
            for (int i = 0; i + 1 < variant.length(); i++) {
                bigrams[i] = variant.substring(i, i + 2);
            }
            if (bigrams.length > 0) {
                output.add(bigrams);
            }
        }
    }

    static boolean hasFuzzyEligible(Analyzer.QueryTokens tokens) {
        for (String token : tokens.lexical()) {
            if (isFuzzyEligible(token)) {
                return true;
            }
        }
        return false;
    }

    static String compileFuzzy(Analyzer.QueryTokens tokens, SearchScope scope, int maxVariants) {
        return compileFuzzy(tokens, scope, null, maxVariants);
    }

    static String compileFuzzy(Analyzer.QueryTokens tokens, SearchScope scope,
                               List<String> vocabulary, int maxVariants) {
        List<String> branches = new ArrayList<>();
        add(branches, scopeExpression(fuzzyLexicalBranch(tokens.lexical(), maxVariants, vocabulary), scope));
        add(branches, pinyinBranch(tokens, scope));
        add(branches, initialsBranch(tokens, scope));
        return joinBranches(branches);
    }

    /** 生成 CJK 编辑距离 ≤1 变体（删除任一字符 / 相邻换位），不含原串，去重保序。 */
    static List<String> cjkVariants(String query, int maxVariants) {
        if (hasNonIndexedLetterOrDigit(query)) {
            // 中文容错只能覆盖 CJK / 假名 / 谚文；混入数字或拉丁词时丢弃它们会改变原查询。
            return new ArrayList<>();
        }
        String sequence = cjkSubsequence(query);
        List<Integer> points = sequence.codePoints().boxed().toList();
        if (points.size() < 2 || points.size() > MAX_CJK_QUERY_LENGTH) {
            return new ArrayList<>();
        }

        List<String> output = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(sequence);

        // 删除：去掉任一字符（用户多打一字）。
        for (int i = 0; i < points.size(); i++) {
            StringBuilder candidate = new StringBuilder(sequence.length());
            for (int j = 0; j < points.size(); j++) {
                if (j != i) {
                    candidate.appendCodePoint(points.get(j));
                }
            }
            addCandidate(output, seen, candidate.toString());
        }
        // 相邻换位（跳过相同字符对）。
        for (int i = 0; i + 1 < points.size(); i++) {
            if (points.get(i).equals(points.get(i + 1))) {
                continue;
            }
            StringBuilder candidate = new StringBuilder(sequence.length());
            for (int j = 0; j < points.size(); j++) {
                if (j == i) {
                    candidate.appendCodePoint(points.get(i + 1));
                } else if (j == i + 1) {
                    candidate.appendCodePoint(points.get(i));
                } else {
                    candidate.appendCodePoint(points.get(j));
                }
            }
            addCandidate(output, seen, candidate.toString());
        }

        if (output.size() > maxVariants) {
            return new ArrayList<>(output.subList(0, maxVariants));
        }
        return output;
    }

    private static void addCandidate(List<String> output, Set<String> seen, String candidate) {
        if (!candidate.isEmpty() && seen.add(candidate)) {
            output.add(candidate);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 分支                                                                */
    /* ------------------------------------------------------------------ */

    private static String lexicalBranch(List<String> terms) {
        List<String> clean = clean(terms);
        if (clean.isEmpty()) {
            return null;
        }

        List<String> chunks = new ArrayList<>();
        int i = 0;
        while (i < clean.size()) {
            if (isCjkBigram(clean.get(i))) {
                int j = i;
                while (j < clean.size() && isCjkBigram(clean.get(j))) {
                    j++;
                }
                List<String> run = clean.subList(i, j);
                chunks.add(quote(run.size() >= 2 ? String.join(" ", run) : run.get(0)));
                i = j;
            } else {
                chunks.add(quote(clean.get(i)));
                i++;
            }
        }

        String last = clean.get(clean.size() - 1);
        boolean prefixLast = isPrefixable(last) && last.length() >= MIN_PREFIX_TOKEN_LENGTH;
        if (prefixLast) {
            int lastIndex = chunks.size() - 1;
            chunks.set(lastIndex, chunks.get(lastIndex) + "*");
        }
        return String.join(" AND ", chunks);
    }

    private static String pinyinBranch(Analyzer.QueryTokens tokens, SearchScope scope) {
        if (tokens.hasCjk() && !tokens.pinyinComplete()) {
            return null;
        }
        List<String> terms = tokens.pinyin();
        List<String> clean = clean(terms);
        if (clean.isEmpty()) {
            return null;
        }
        if (clean.size() == 1) {
            String last = clean.get(0);
            return scopedColumns(pinyinColumns(scope),
                    quotedAnd(clean, last.length() >= MIN_PREFIX_TOKEN_LENGTH));
        }
        return scopedColumns(pinyinColumns(scope), quote(String.join(" ", clean)));
    }

    private static String initialsBranch(Analyzer.QueryTokens tokens, SearchScope scope) {
        if (tokens.hasCjk() && !tokens.pinyinComplete()) {
            return null;
        }
        String initials = tokens.pinyinInitials();
        if (initials == null || initials.isBlank()) {
            return null;
        }
        List<String> terms = new ArrayList<>();
        for (String part : initials.trim().split("\\s+")) {
            if (!part.isEmpty()) {
                terms.add(part);
            }
        }
        if (terms.isEmpty()) {
            return null;
        }
        boolean prefixLast = terms.get(terms.size() - 1).length() >= MIN_INITIALS_PREFIX_LENGTH;
        String body = quotedAnd(terms, prefixLast);
        return scopedColumns(initialsColumns(scope), body);
    }

    private static String crossLingualBranch(List<String> terms) {
        List<String> clean = clean(terms);
        if (clean.isEmpty()) {
            return null;
        }
        if (clean.size() == 1) {
            return quote(clean.get(0));
        }
        StringBuilder builder = new StringBuilder("(");
        for (int i = 0; i < clean.size(); i++) {
            if (i > 0) {
                builder.append(" OR ");
            }
            builder.append(quote(clean.get(i)));
        }
        return builder.append(")").toString();
    }

    private static String fuzzyLexicalBranch(List<String> terms, int maxVariants, List<String> vocabulary) {
        List<String> clean = clean(terms);
        if (clean.isEmpty()) {
            return null;
        }

        boolean useVocabulary = vocabulary != null && !vocabulary.isEmpty();
        List<Integer> expandable = new ArrayList<>();
        for (int i = 0; i < clean.size(); i++) {
            if (isFuzzyEligible(clean.get(i))) {
                expandable.add(i);
            }
        }

        Map<Integer, List<String>> groups = new HashMap<>();
        for (int index : expandable) {
            boolean prefix = index == clean.size() - 1
                    && isPrefixable(clean.get(index))
                    && clean.get(index).length() >= MIN_PREFIX_TOKEN_LENGTH;
            if (!useVocabulary) {
                List<String> expressions = new ArrayList<>();
                for (String variant : editDistanceOneVariants(clean.get(index))) {
                    expressions.add(prefix ? quote(variant) + "*" : quote(variant));
                }
                groups.put(index, expressions);
                continue;
            }
            groups.put(index, vocabularyExpressions(clean.get(index), vocabulary, maxVariants));
        }

        int total = 0;
        for (List<String> list : groups.values()) {
            total += list.size();
        }
        if (!useVocabulary && total > maxVariants && !expandable.isEmpty()) {
            int last = expandable.get(expandable.size() - 1);
            boolean lastPrefix = last == clean.size() - 1
                    && isPrefixable(clean.get(last))
                    && clean.get(last).length() >= MIN_PREFIX_TOKEN_LENGTH;
            List<String> full = new ArrayList<>();
            for (String variant : editDistanceOneVariants(clean.get(last))) {
                full.add(lastPrefix ? quote(variant) + "*" : quote(variant));
            }
            groups.clear();
            groups.put(last, full.size() > maxVariants ? new ArrayList<>(full.subList(0, maxVariants)) : full);
        }

        List<String> chunks = new ArrayList<>();
        int index = 0;
        while (index < clean.size()) {
            if (isCjkBigram(clean.get(index))) {
                int j = index;
                while (j < clean.size() && isCjkBigram(clean.get(j))) {
                    j++;
                }
                List<String> run = clean.subList(index, j);
                chunks.add(quote(run.size() >= 2 ? String.join(" ", run) : run.get(0)));
                index = j;
                continue;
            }

            boolean prefix = index == clean.size() - 1
                    && isPrefixable(clean.get(index))
                    && clean.get(index).length() >= MIN_PREFIX_TOKEN_LENGTH;
            List<String> list = groups.get(index);
            if (list != null && !list.isEmpty()) {
                chunks.add(list.size() == 1 ? list.get(0) : "(" + String.join(" OR ", list) + ")");
            } else if (useVocabulary && isFuzzyEligible(clean.get(index))) {
                List<String> bare = new ArrayList<>();
                for (String variant : editDistanceOneVariants(clean.get(index))) {
                    bare.add(prefix ? quote(variant) + "*" : quote(variant));
                }
                if (bare.size() > maxVariants) {
                    bare = new ArrayList<>(bare.subList(0, maxVariants));
                }
                chunks.add(bare.size() == 1 ? bare.get(0) : "(" + String.join(" OR ", bare) + ")");
            } else {
                String quoted = quote(clean.get(index));
                chunks.add(prefix ? quoted + "*" : quoted);
            }
            index++;
        }
        return String.join(" AND ", chunks);
    }

    private static List<String> vocabularyExpressions(String word, List<String> vocabulary, int maxVariants) {
        List<String> output = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (String variant : editDistanceOneVariants(word)) {
            FuzzyVocabulary.Range range = FuzzyVocabulary.prefixRange(vocabulary, variant);
            if (range.isEmpty()) {
                continue;
            }

            if (range.end() - range.start() <= MAX_EXACT_TERMS_PER_VARIANT) {
                for (int i = range.start(); i < range.end() && output.size() < maxVariants; i++) {
                    String term = vocabulary.get(i);
                    if (!FuzzyVocabulary.withinOne(word, term)) {
                        continue;
                    }
                    if (seen.add(term)) {
                        output.add(quote(term));
                    }
                }
                continue;
            }

            String expression = quote(variant) + "*";
            if (seen.add(expression) && output.size() < maxVariants) {
                output.add(expression);
            }
        }

        return output;
    }

    /* ------------------------------------------------------------------ */
    /* 工具                                                                */
    /* ------------------------------------------------------------------ */

    private static String joinBranches(List<String> branches) {
        if (branches.isEmpty()) {
            return null;
        }
        String joined = String.join(" OR ", branches);
        return branches.size() == 1 ? joined : "(" + joined + ")";
    }

    /** 把任意完整表达式限制到指定 FTS 列；ALL 不额外包裹。 */
    static String scopeExpression(String body, SearchScope scope) {
        if (body == null || body.isBlank() || scope == null || scope == SearchScope.ALL) {
            return body;
        }
        String columns = scope == SearchScope.METADATA ? METADATA_COLUMNS : LYRICS_COLUMNS;
        return scopedColumns(columns, body);
    }

    private static String scopedColumns(String columns, String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        if (columns == null || columns.isBlank()) {
            return body;
        }
        return "{" + columns + "} : (" + body + ")";
    }

    private static String pinyinColumns(SearchScope scope) {
        return switch (scope) {
            case METADATA -> "pinyin_meta_tok";
            case LYRICS -> "pinyin_lyrics_tok";
            default -> PINYIN_COLUMNS;
        };
    }

    private static String initialsColumns(SearchScope scope) {
        return switch (scope) {
            case METADATA -> "initials_meta_tok";
            case LYRICS -> "initials_lyrics_tok";
            default -> INITIALS_COLUMNS;
        };
    }

    private static String readingColumns(SearchScope scope) {
        return switch (scope) {
            case METADATA -> "reading_meta_tok";
            case LYRICS -> "lyrics_rom_tok";
            default -> READING_COLUMNS;
        };
    }

    private static void add(List<String> branches, String branch) {
        if (branch != null) {
            branches.add(branch);
        }
    }

    private static List<String> clean(List<String> terms) {
        List<String> clean = new ArrayList<>(terms.size());
        for (String term : terms) {
            if (term == null) {
                continue;
            }
            String trimmed = term.trim();
            if (!trimmed.isEmpty()) {
                clean.add(trimmed);
            }
        }
        return clean;
    }

    private static String quotedAnd(List<String> terms, boolean prefixLast) {
        List<String> parts = new ArrayList<>(terms.size());
        for (int i = 0; i < terms.size(); i++) {
            boolean prefixed = i == terms.size() - 1 && prefixLast;
            parts.add(prefixed ? quote(terms.get(i)) + "*" : quote(terms.get(i)));
        }
        return String.join(" AND ", parts);
    }

    /** 生成编辑距离 ≤1 变体（含原词；去重保序）。 */
    private static List<String> editDistanceOneVariants(String word) {
        List<String> output = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        output.add(word);
        seen.add(word);

        for (int i = 0; i < word.length(); i++) {
            addVariantSet(output, seen, word.substring(0, i) + word.substring(i + 1));
        }
        for (int i = 0; i + 1 < word.length(); i++) {
            if (word.charAt(i) == word.charAt(i + 1)) {
                continue;
            }
            char[] chars = word.toCharArray();
            char tmp = chars[i];
            chars[i] = chars[i + 1];
            chars[i + 1] = tmp;
            addVariantSet(output, seen, new String(chars));
        }
        for (int i = 0; i < word.length(); i++) {
            String alternatives = SUBSTITUTION_ALTERNATIVES.get(word.charAt(i));
            if (alternatives == null) {
                continue;
            }
            for (int k = 0; k < alternatives.length(); k++) {
                addVariantSet(output, seen,
                        word.substring(0, i) + alternatives.charAt(k) + word.substring(i + 1));
            }
        }
        return output;
    }

    private static void addVariantSet(List<String> output, Set<String> seen, String candidate) {
        if (!candidate.isEmpty() && seen.add(candidate)) {
            output.add(candidate);
        }
    }

    private static boolean isCjkBigram(String token) {
        int count = token.codePointCount(0, token.length());
        if (count != 2) {
            return false;
        }
        return token.codePoints().allMatch(SearchQuery::isIndexedScript);
    }

    private static boolean isPrefixable(String token) {
        return token.codePoints().noneMatch(SearchQuery::isIndexedScript);
    }

    private static boolean isFuzzyEligible(String token) {
        if (token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIndexedScript(int codePoint) {
        return ScriptRanges.isCjk(codePoint) || ScriptRanges.isSubstringScript(codePoint);
    }

    private static boolean hasIndexedScript(String token) {
        return token.codePoints().anyMatch(SearchQuery::isIndexedScript);
    }

    private static String cjkSubsequence(String query) {
        StringBuilder builder = new StringBuilder(query.length());
        for (int i = 0; i < query.length(); ) {
            int cp = query.codePointAt(i);
            if (isIndexedScript(cp)) {
                builder.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return builder.toString();
    }

    private static boolean hasNonIndexedLetterOrDigit(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isLetterOrDigit(cp) && !isIndexedScript(cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }
}
