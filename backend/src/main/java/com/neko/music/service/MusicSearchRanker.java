package com.neko.music.service;

import com.neko.music.search.ScriptRanges;
import com.neko.music.util.ChineseConverter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 曲库搜索相关度评分：先把查询串拆成「歌名 / 歌手」候选词条，再按层级打分。
 *
 * <p>客户端常把歌名与歌手一起提交（{@code 周杰伦，晴天}、{@code 晴天 周杰伦}、
 * {@code 周杰伦-晴天}、{@code 晴天/周杰伦}）。评分按「歌名 &gt; 歌手 &gt; 专辑」分层：
 * 只要歌名（含标题拼音列）命中，就一定排在仅歌手命中的曲目前面；查询串没有分隔符时，
 * 退化成与旧的整串模糊匹配一致的相对顺序。
 *
 * <p>本类不做数据库访问，输入是纯字符串，便于单测覆盖排序规则。
 */
public final class MusicSearchRanker {

    private MusicSearchRanker() {
    }

    /** 一条曲目的可检索字段（拼音列来自 music 表的预计算列）。 */
    public record MusicFields(
            String title, String artist, String album,
            String titlePinyin, String titlePinyinInitials, String titleWordInitials,
            String artistPinyin, String artistPinyinInitials, String artistWordInitials,
            String albumPinyin) {
    }

    /** 词条数上限：避免超长查询把 SQL 条件与角色分配组合撑爆。 */
    private static final int MAX_TERMS = 6;
    /**
     * 词条分隔符：空白与常见的歌名/歌手连接符。
     * 不含英文撇号，避免把 {@code Don't} 这类歌名切坏。
     */
    private static final String TERM_SEPARATORS =
            " \t\u00A0\u3000-\u2013\u2014_+~～/\\|｜,，、;；:：&＆!！?？(（)）[【]】{《}》<>·•";
    /**
     * 版本/翻唱修饰词不是歌名主体：用户搜「歌名 (Cover)」时，
     * 不能把 Cover 当成一个独立的正向关键词，否则会召回大量无关翻唱。
     */
    private static final Pattern WEAK_QUALIFIER_GROUP = Pattern.compile(
            "(?iu)\\s*[\\(（\\[【]\\s*"
                    + "(?:cover|covers|翻唱|翻唱版|伴奏|instrumental|off\\s*vocal|karaoke)"
                    + "\\s*[\\)）\\]】]");
    private static final Set<String> WEAK_TERMS = Set.of(
            "cover", "covers", "翻唱", "翻唱版", "伴奏", "instrumental", "off vocal", "karaoke");
    private static final Set<String> LOW_INFORMATION_TERMS = Set.of(
            "a", "an", "and", "are", "be", "do", "for", "i", "in", "is", "it", "its",
            "la", "me", "my", "na", "of", "oh", "on", "or", "the", "to", "we",
            "with", "ya", "yeah", "you", "your");
    /**
     * 「歌名 / 歌手 / 专辑」三级角色的权重（10 的幂）。
     * 单字段得分不超过 100，所以低一级角色拿满分也越不过高一级角色的最低命中分。
     */
    private static final int TITLE_ROLE_MULTIPLIER = 10_000;
    private static final int ARTIST_ROLE_MULTIPLIER = 100;
    /** 连写拆分时每段的最短长度（避免把单字当成歌名或歌手）。 */
    private static final int MIN_CONCATENATION_PART_LENGTH = 2;
    /** 连写拆分的整串长度上限（避免超长串产生过多拆分假设与 SQL 子查询）。 */
    private static final int MAX_CONCATENATION_LENGTH = 10;
    /** 英文长句（>= 4 个单词）视为歌词片段，不做「歌名 + 歌手」切分。 */
    private static final int FREE_TEXT_LATIN_WORD_THRESHOLD = 4;
    /** 专辑拼音命中沿用旧实现的固定分（专辑角色本就排在最后）。 */
    private static final int ALBUM_PINYIN_SCORE = 90;
    /** 子串命中至少要有两个字符，避免单字查询吞掉整库。 */
    private static final int MIN_CONTAINS_CODE_POINTS = 2;
    /** 非单词边界的拉丁子串至少要有三个字符。 */
    private static final int MIN_LATIN_CONTAINS_CODE_POINTS = 3;
    /** CJK 等无空格脚本的子串覆盖率下限。 */
    private static final double MIN_CONTAINS_COVERAGE = 0.5;

    private static final FieldWeights TITLE_WEIGHTS = new FieldWeights(100, 80, 60, 95, 75, 55);
    private static final FieldWeights ARTIST_WEIGHTS = new FieldWeights(50, 40, 30, 45, 35, 25);
    private static final FieldWeights ALBUM_WEIGHTS = new FieldWeights(20, 15, 10, 18, 13, 8);

    /** 单字段文本匹配各命中类型的加分。 */
    private record FieldWeights(
            int exact, int prefix, int contains,
            int simplifiedExact, int simplifiedPrefix, int simplifiedContains) {
    }

    private record FieldMatch(int score, boolean confident) {
        private static final FieldMatch NONE = new FieldMatch(0, false);

        private static FieldMatch of(int score, boolean confident) {
            return score <= 0 ? NONE : new FieldMatch(score, confident);
        }
    }

    /**
     * 把查询串切成词条：按分隔符拆分、去空白、去重并限制条数。
     * 单关键词查询返回单元素列表，行为与旧的整串匹配一致。
     */
    public static List<String> splitTerms(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return List.of();
        }
        String trimmed = rawQuery.trim();
        String withoutQualifierGroups = WEAK_QUALIFIER_GROUP.matcher(trimmed).replaceAll(" ").trim();
        String searchable = withoutQualifierGroups.isEmpty() ? trimmed : withoutQualifierGroups;
        if (isFreeTextPhrase(searchable)) {
            return List.of(searchable);
        }

        List<String> terms = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String piece : splitOnSeparators(searchable)) {
            String term = piece.trim();
            if (term.isEmpty() || !seen.add(term)) {
                continue;
            }
            terms.add(term);
            if (terms.size() >= MAX_TERMS) {
                break;
            }
        }
        if (terms.isEmpty()) {
            return List.of(searchable);
        }
        List<String> strongTerms = terms.stream()
                .filter(term -> !WEAK_TERMS.contains(term.toLowerCase(Locale.ROOT)))
                .toList();
        return strongTerms.isEmpty() ? List.copyOf(terms) : strongTerms;
    }

    /**
     * 层级评分：枚举「词条 → 角色（歌名 / 歌手 / 专辑）」的全部指派，取分值最高的一种。
     * 这样 {@code 周杰伦，晴天} 与 {@code 晴天/周杰伦} 这类顺序不定的输入都能命中同一首曲目。
     * 角色主分相同时，命中词条更多的候选优先。
     */
    public static int score(MusicFields fields, List<String> terms) {
        if (fields == null || terms == null || terms.isEmpty()) {
            return 0;
        }

        int count = terms.size();
        int[] titleScores = new int[count];
        int[] artistScores = new int[count];
        int[] albumScores = new int[count];
        for (int i = 0; i < count; i++) {
            String term = terms.get(i);
            titleScores[i] = titleScore(fields, term);
            artistScores[i] = artistScore(fields, term);
            albumScores[i] = albumScore(fields, term);
        }
        return bestRoleAssignment(titleScores, artistScores, albumScores);
    }

    /**
     * 严格相关性评分：每个查询词条都必须至少命中一个字段，且子串 / 拼音包含必须达到
     * 最低长度与覆盖率。只有该评分大于 0 的候选才允许进入正式结果。
     */
    public static int scoreConfident(MusicFields fields, List<String> terms) {
        if (fields == null || terms == null || terms.isEmpty()) {
            return 0;
        }

        int count = terms.size();
        FieldMatch[] titleMatches = new FieldMatch[count];
        FieldMatch[] artistMatches = new FieldMatch[count];
        FieldMatch[] albumMatches = new FieldMatch[count];
        for (int i = 0; i < count; i++) {
            String term = terms.get(i);
            titleMatches[i] = titleMatch(fields, term);
            artistMatches[i] = artistMatch(fields, term);
            albumMatches[i] = albumMatch(fields, term);
        }
        return bestConfidentRoleAssignment(titleMatches, artistMatches, albumMatches);
    }

    /**
     * 「歌名 + 歌手」直接连写（如 晴天周杰伦、周杰伦晴天）的拆分假设：
     * 每个假设把整串切成前后两段，两段分别充当歌名与歌手（两种顺序都会试）。
     * 只对「没有分隔符、整串都是中文、长度 4~10」的查询生成，其余返回空列表。
     */
    public static List<List<String>> splitConcatenations(String rawQuery) {
        if (rawQuery == null) {
            return List.of();
        }
        String trimmed = rawQuery.trim();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < MIN_CONCATENATION_PART_LENGTH * 2
                || length > MAX_CONCATENATION_LENGTH
                || !isAllCjk(trimmed)) {
            return List.of();
        }

        List<Integer> boundaries = new ArrayList<>(length + 1);
        boundaries.add(0);
        for (int i = 0; i < trimmed.length(); ) {
            int cp = trimmed.codePointAt(i);
            i += Character.charCount(cp);
            boundaries.add(i);
        }
        List<List<String>> hypotheses = new ArrayList<>();
        for (int split = MIN_CONCATENATION_PART_LENGTH;
                split <= length - MIN_CONCATENATION_PART_LENGTH; split++) {
            int offset = boundaries.get(split);
            hypotheses.add(List.of(trimmed.substring(0, offset), trimmed.substring(offset)));
        }
        return List.copyOf(hypotheses);
    }

    /**
     * 连写查询的得分：只认「一段命中歌名、另一段命中歌手」的拆分假设，
     * 两部分都必须真的命中，避免把单首歌名（如 想见你只想见你）拆成无意义的片段。
     */
    public static int concatenatedScore(MusicFields fields, List<List<String>> hypotheses) {
        if (fields == null || hypotheses == null || hypotheses.isEmpty()) {
            return 0;
        }

        int best = 0;
        for (List<String> hypothesis : hypotheses) {
            String first = hypothesis.get(0);
            String second = hypothesis.get(1);
            // 前段当歌名（晴天周杰伦），或后段当歌名（周杰伦晴天）
            best = Math.max(best, pairedScore(titleScore(fields, first), artistScore(fields, second)));
            best = Math.max(best, pairedScore(titleScore(fields, second), artistScore(fields, first)));
        }
        return best;
    }

    /** 连写查询的严格版本：歌名段与歌手段都必须达到强匹配。 */
    public static int concatenatedConfidentScore(
            MusicFields fields, List<List<String>> hypotheses) {
        if (fields == null || hypotheses == null || hypotheses.isEmpty()) {
            return 0;
        }

        int best = 0;
        for (List<String> hypothesis : hypotheses) {
            String first = hypothesis.get(0);
            String second = hypothesis.get(1);
            best = Math.max(best,
                    pairedConfidentScore(titleMatch(fields, first), artistMatch(fields, second)));
            best = Math.max(best,
                    pairedConfidentScore(titleMatch(fields, second), artistMatch(fields, first)));
        }
        return best;
    }

    private static int titleScore(MusicFields fields, String term) {
        String termLower = term.toLowerCase(Locale.ROOT);
        return fieldScore(fields.title(), fields.titlePinyin(), fields.titlePinyinInitials(),
                fields.titleWordInitials(), termLower, TITLE_WEIGHTS);
    }

    private static int artistScore(MusicFields fields, String term) {
        String termLower = term.toLowerCase(Locale.ROOT);
        return fieldScore(fields.artist(), fields.artistPinyin(), fields.artistPinyinInitials(),
                fields.artistWordInitials(), termLower, ARTIST_WEIGHTS);
    }

    private static int albumScore(MusicFields fields, String term) {
        String termLower = term.toLowerCase(Locale.ROOT);
        return Math.max(textScore(fields.album(), termLower, ALBUM_WEIGHTS),
                pinyinFieldScore(null, fields.albumPinyin(), null, termLower));
    }

    private static FieldMatch titleMatch(MusicFields fields, String term) {
        String termLower = term.toLowerCase(Locale.ROOT);
        return fieldMatch(fields.title(), fields.titlePinyin(), fields.titlePinyinInitials(),
                fields.titleWordInitials(), termLower, TITLE_WEIGHTS);
    }

    private static FieldMatch artistMatch(MusicFields fields, String term) {
        String termLower = term.toLowerCase(Locale.ROOT);
        return fieldMatch(fields.artist(), fields.artistPinyin(), fields.artistPinyinInitials(),
                fields.artistWordInitials(), termLower, ARTIST_WEIGHTS);
    }

    private static FieldMatch albumMatch(MusicFields fields, String term) {
        String termLower = term.toLowerCase(Locale.ROOT);
        FieldMatch text = textMatch(fields.album(), termLower, ALBUM_WEIGHTS);
        FieldMatch pinyin = pinyinMatch(null, fields.albumPinyin(), null, termLower);
        return better(text, pinyin);
    }

    private static int pairedScore(int titleScore, int artistScore) {
        if (titleScore <= 0 || artistScore <= 0) {
            return 0;
        }
        return layeredScore(titleScore, artistScore, 0) + 200;
    }

    private static int pairedConfidentScore(FieldMatch title, FieldMatch artist) {
        if (!title.confident() || !artist.confident()) {
            return 0;
        }
        return layeredScore(title.score(), artist.score(), 0) + 200;
    }

    /**
     * 整串「元数据相关度」强度（0~170）：只看完整查询串，不看拆分后的词条。
     * 用来判断站内元数据是否已经足够好、无需再搜歌词（阈值与旧实现保持一致）。
     */
    public static int wholeQueryStrength(MusicFields fields, String rawQuery) {
        if (fields == null || rawQuery == null || rawQuery.isBlank()) {
            return 0;
        }

        String trimmed = rawQuery.trim();
        String queryLower = trimmed.toLowerCase(Locale.ROOT);
        String querySimplifiedLower = ChineseConverter.toSimplified(trimmed).toLowerCase(Locale.ROOT);

        int score = textScoreWithSimplified(fields.title(), queryLower, querySimplifiedLower, TITLE_WEIGHTS)
                + textScoreWithSimplified(fields.artist(), queryLower, querySimplifiedLower, ARTIST_WEIGHTS)
                + textScoreWithSimplified(fields.album(), queryLower, querySimplifiedLower, ALBUM_WEIGHTS);
        return Math.max(score, legacyPinyinScore(fields, queryLower));
    }

    private static int fieldScore(
            String value, String pinyin, String pinyinInitials, String wordInitials,
            String term, FieldWeights weights) {
        return Math.max(textScore(value, term, weights),
                pinyinFieldScore(wordInitials, pinyin, pinyinInitials, term));
    }

    private static FieldMatch fieldMatch(
            String value, String pinyin, String pinyinInitials, String wordInitials,
            String term, FieldWeights weights) {
        return better(textMatch(value, term, weights),
                pinyinMatch(wordInitials, pinyin, pinyinInitials, term));
    }

    private static int textScore(String value, String termLower, FieldWeights weights) {
        return textScoreWithSimplified(value, termLower, ChineseConverter.toSimplified(termLower), weights);
    }

    private static FieldMatch textMatch(String value, String termLower, FieldWeights weights) {
        return textMatchWithSimplified(
                value, termLower, ChineseConverter.toSimplified(termLower), weights);
    }

    /**
     * 单字段文本匹配打分：精确 &gt; 前缀 &gt; 子串，并兼容繁简体变体。
     */
    private static int textScoreWithSimplified(
            String value, String termLower, String termSimplifiedLower, FieldWeights weights) {
        if (value == null || value.isEmpty()) {
            return 0;
        }

        String valueLower = value.toLowerCase(Locale.ROOT);
        String valueSimplified = ChineseConverter.toSimplified(value).toLowerCase(Locale.ROOT);

        if (valueLower.equals(termLower)) {
            return weights.exact();
        }
        if (valueLower.startsWith(termLower)) {
            return weights.prefix();
        }
        if (valueLower.contains(termLower)) {
            return weights.contains();
        }
        if (valueSimplified.equals(termLower) || valueLower.equals(termSimplifiedLower)) {
            return weights.simplifiedExact();
        }
        if (valueSimplified.startsWith(termLower) || valueLower.startsWith(termSimplifiedLower)) {
            return weights.simplifiedPrefix();
        }
        if (valueSimplified.contains(termLower) || valueLower.contains(termSimplifiedLower)) {
            return weights.simplifiedContains();
        }
        return 0;
    }

    private static FieldMatch textMatchWithSimplified(
            String value, String termLower, String termSimplifiedLower, FieldWeights weights) {
        if (value == null || value.isEmpty()) {
            return FieldMatch.NONE;
        }

        String valueLower = value.toLowerCase(Locale.ROOT);
        String valueSimplified = ChineseConverter.toSimplified(value).toLowerCase(Locale.ROOT);
        boolean lowInformation = isLowInformationTerm(termLower);

        if (valueLower.equals(termLower)) {
            return FieldMatch.of(weights.exact(), true);
        }
        if (valueLower.startsWith(termLower)) {
            return FieldMatch.of(weights.prefix(), !lowInformation);
        }
        if (valueLower.contains(termLower)) {
            return FieldMatch.of(weights.contains(), isStrongContains(valueLower, termLower));
        }
        if (valueSimplified.equals(termLower) || valueLower.equals(termSimplifiedLower)) {
            return FieldMatch.of(weights.simplifiedExact(), true);
        }
        if (valueSimplified.startsWith(termLower) || valueLower.startsWith(termSimplifiedLower)) {
            return FieldMatch.of(weights.simplifiedPrefix(), !lowInformation);
        }
        if (valueSimplified.contains(termLower) || valueLower.contains(termSimplifiedLower)) {
            return FieldMatch.of(weights.simplifiedContains(),
                    isStrongContains(valueSimplified, termLower)
                            || isStrongContains(valueLower, termSimplifiedLower));
        }
        return FieldMatch.NONE;
    }

    /**
     * 单字段拼音匹配打分（按优先级取一档，不累加）：
     * 词首字母精确 &gt; 词首字母前缀 &gt; 词首字母子串 &gt; 全拼子串 &gt; 首字母子串。
     */
    private static int pinyinFieldScore(
            String wordInitials, String pinyin, String initials, String termLower) {
        if (wordInitials != null) {
            if (wordInitials.equals(termLower)) {
                return 95;
            }
            if (wordInitials.startsWith(termLower)) {
                return 90;
            }
            if (wordInitials.contains(termLower)) {
                return 80;
            }
        }
        if (pinyin != null && pinyin.contains(termLower)) {
            return 85;
        }
        if (initials != null && initials.contains(termLower)) {
            return 75;
        }
        return 0;
    }

    private static FieldMatch pinyinMatch(
            String wordInitials, String pinyin, String initials, String termLower) {
        if (termLower == null || termLower.isBlank()) {
            return FieldMatch.NONE;
        }
        boolean lowInformation = isLowInformationTerm(termLower);
        if (wordInitials != null) {
            if (wordInitials.equals(termLower)) {
                return FieldMatch.of(95, true);
            }
            if (wordInitials.startsWith(termLower)) {
                return FieldMatch.of(90, !lowInformation);
            }
            if (wordInitials.contains(termLower)) {
                return FieldMatch.of(80, isStrongPinyinContains(wordInitials, termLower));
            }
        }
        if (pinyin != null && pinyin.contains(termLower)) {
            return FieldMatch.of(85, isStrongPinyinContains(pinyin, termLower));
        }
        if (initials != null && initials.contains(termLower)) {
            return FieldMatch.of(75, isStrongPinyinContains(initials, termLower));
        }
        return FieldMatch.NONE;
    }

    /** 整串拼音匹配：歌名 → 歌手 → 专辑，命中即止（与旧实现一致）。 */
    private static int legacyPinyinScore(MusicFields fields, String termLower) {
        int title = pinyinFieldScore(fields.titleWordInitials(), fields.titlePinyin(),
                fields.titlePinyinInitials(), termLower);
        if (title > 0) {
            return title;
        }
        int artist = pinyinFieldScore(fields.artistWordInitials(), fields.artistPinyin(),
                fields.artistPinyinInitials(), termLower);
        if (artist > 0) {
            return artist;
        }
        if (fields.albumPinyin() != null && fields.albumPinyin().contains(termLower)) {
            return ALBUM_PINYIN_SCORE;
        }
        return 0;
    }

    private static int bestRoleAssignment(int[] titleScores, int[] artistScores, int[] albumScores) {
        int count = titleScores.length;
        int combinations = 1;
        for (int i = 0; i < count; i++) {
            combinations *= 3;
        }

        int best = 0;
        for (int mask = 0; mask < combinations; mask++) {
            int title = 0;
            int artist = 0;
            int album = 0;
            int matchedTerms = 0;
            int rest = mask;
            for (int i = 0; i < count; i++) {
                int role = rest % 3;
                rest /= 3;
                switch (role) {
                    case 0 -> {
                        if (titleScores[i] > 0) {
                            matchedTerms++;
                        }
                        title = Math.max(title, titleScores[i]);
                    }
                    case 1 -> {
                        if (artistScores[i] > 0) {
                            matchedTerms++;
                        }
                        artist = Math.max(artist, artistScores[i]);
                    }
                    default -> {
                        if (albumScores[i] > 0) {
                            matchedTerms++;
                        }
                        album = Math.max(album, albumScores[i]);
                    }
                }
            }
            // 角色内仍取最高单项分，低两位用于“命中词覆盖数”的稳定排序。
            int score = layeredScore(title, artist, album) + matchedTerms * 100;
            if (score > best) {
                best = score;
            }
        }
        return best;
    }

    private static int bestConfidentRoleAssignment(
            FieldMatch[] titleMatches, FieldMatch[] artistMatches, FieldMatch[] albumMatches) {
        int count = titleMatches.length;
        int combinations = 1;
        for (int i = 0; i < count; i++) {
            combinations *= 3;
        }

        int best = 0;
        for (int mask = 0; mask < combinations; mask++) {
            int title = 0;
            int artist = 0;
            int album = 0;
            int matchedTerms = 0;
            int rest = mask;
            boolean valid = true;
            for (int i = 0; i < count; i++) {
                int role = rest % 3;
                rest /= 3;
                FieldMatch match = switch (role) {
                    case 0 -> titleMatches[i];
                    case 1 -> artistMatches[i];
                    default -> albumMatches[i];
                };
                if (match == null || !match.confident()) {
                    valid = false;
                    break;
                }
                matchedTerms++;
                switch (role) {
                    case 0 -> title = Math.max(title, match.score());
                    case 1 -> artist = Math.max(artist, match.score());
                    default -> album = Math.max(album, match.score());
                }
            }
            if (!valid || matchedTerms != count) {
                continue;
            }
            best = Math.max(best, layeredScore(title, artist, album) + matchedTerms * 100);
        }
        return best;
    }

    private static FieldMatch better(FieldMatch first, FieldMatch second) {
        if (first.confident() != second.confident()) {
            return first.confident() ? first : second;
        }
        if (first.score() >= second.score()) {
            return first;
        }
        return second;
    }

    /** 单词是否为不承载检索意图的通用短词（避免 `a` / `the` 吞掉整库）。 */
    public static boolean isLowInformationTerm(String term) {
        if (term == null || term.isBlank()) {
            return true;
        }
        if (LOW_INFORMATION_TERMS.contains(term)) {
            return true;
        }
        int count = term.codePointCount(0, term.length());
        if (count != 1) {
            return false;
        }
        int cp = term.codePointAt(0);
        if (cp < 0x80 && Character.isLetterOrDigit(cp)) {
            return true;
        }
        return false;
    }

    private static boolean isStrongContains(String value, String term) {
        if (value == null || term == null || term.isEmpty() || isLowInformationTerm(term)) {
            return false;
        }
        int termLength = term.codePointCount(0, term.length());
        int valueLength = value.codePointCount(0, value.length());
        if (termLength < MIN_CONTAINS_CODE_POINTS || valueLength <= 0) {
            return false;
        }
        if (term.codePoints().anyMatch(MusicSearchRanker::isCjkCodePoint)) {
            return (double) termLength / valueLength >= MIN_CONTAINS_COVERAGE;
        }
        if (hasAsciiWordPrefix(value, term)) {
            return true;
        }
        return termLength >= MIN_LATIN_CONTAINS_CODE_POINTS
                && (double) termLength / valueLength >= MIN_CONTAINS_COVERAGE;
    }

    private static boolean isStrongPinyinContains(String value, String term) {
        if (value == null || term == null || term.isBlank() || isLowInformationTerm(term)) {
            return false;
        }
        int termLength = term.length();
        int valueLength = value.length();
        if (termLength < MIN_CONTAINS_CODE_POINTS || valueLength <= 0) {
            return false;
        }
        return (double) termLength / valueLength >= MIN_CONTAINS_COVERAGE;
    }

    private static boolean hasAsciiWordPrefix(String value, String term) {
        if (term.length() < MIN_LATIN_CONTAINS_CODE_POINTS || !isAsciiWord(term)) {
            return false;
        }
        int from = 0;
        while (from <= value.length() - term.length()) {
            int index = value.indexOf(term, from);
            if (index < 0) {
                return false;
            }
            boolean leftBoundary = index == 0
                    || !Character.isLetterOrDigit(value.codePointBefore(index));
            if (leftBoundary) {
                return true;
            }
            from = index + 1;
        }
        return false;
    }

    private static boolean isAsciiWord(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCjkCodePoint(int codePoint) {
        return ScriptRanges.isCjk(codePoint) || ScriptRanges.isSubstringScript(codePoint);
    }

    private static int layeredScore(int titleScore, int artistScore, int albumScore) {
        return titleScore * TITLE_ROLE_MULTIPLIER + artistScore * ARTIST_ROLE_MULTIPLIER + albumScore;
    }

    private static List<String> splitOnSeparators(String text) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (TERM_SEPARATORS.indexOf(c) >= 0) {
                if (current.length() > 0) {
                    pieces.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    /** 纯英文长句（>= 4 个单词、无中日韩字符）按歌词片段处理，不拆分。 */
    private static boolean isFreeTextPhrase(String query) {
        int latinWords = 0;
        int cjkCount = 0;
        boolean inLatinWord = false;
        for (int i = 0; i < query.length(); ) {
            int cp = query.codePointAt(i);
            if (isCjk(cp)) {
                cjkCount++;
                inLatinWord = false;
                i += Character.charCount(cp);
                continue;
            }
            boolean latinLetter = Character.isLetter(cp)
                    && Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN;
            if (latinLetter) {
                if (!inLatinWord) {
                    latinWords++;
                    inLatinWord = true;
                }
            } else {
                inLatinWord = false;
            }
            i += Character.charCount(cp);
        }
        return cjkCount == 0 && latinWords >= FREE_TEXT_LATIN_WORD_THRESHOLD;
    }

    private static boolean isAllCjk(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (!isCjk(cp)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    private static boolean isCjk(char c) {
        return isCjk((int) c);
    }

    private static boolean isCjk(int codePoint) {
        return ScriptRanges.isCjk(codePoint) || ScriptRanges.isSubstringScript(codePoint);
    }
}
