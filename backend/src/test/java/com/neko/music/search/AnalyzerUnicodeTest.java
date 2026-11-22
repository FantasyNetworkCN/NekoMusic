package com.neko.music.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyzerUnicodeTest {

    private static final int[][] SUPPLEMENTARY_CJK_RANGES = {
            {0x20000, 0x2A6DF},
            {0x2A700, 0x2B73F},
            {0x2B740, 0x2B81F},
            {0x2B820, 0x2CEAF},
            {0x2CEB0, 0x2EBEF},
            {0x2EBF0, 0x2EE5F},
            {0x2F800, 0x2FA1F},
            {0x30000, 0x3134F},
            {0x31350, 0x323AF},
    };

    @Test
    void everySupplementaryCjkRangeIsRecognizedByCodePoint() {
        for (int[] range : SUPPLEMENTARY_CJK_RANGES) {
            for (int cp = range[0]; cp <= range[1]; cp++) {
                assertTrue(ScriptRanges.isCjk(cp), "U+" + Integer.toHexString(cp));
            }
        }
    }

    @Test
    void analyzerKeepsSupplementaryCharactersAndFollowingLatin() {
        for (int cp : List.of(0x20000, 0x2A6D6, 0x2EBF0, 0x2FA1F, 0x30000, 0x323AF)) {
            String rare = new String(Character.toChars(cp));
            Analyzer.QueryTokens tokens = Analyzer.analyzeQuery(rare + "a");
            assertTrue(tokens.lexical().contains(rare), "U+" + Integer.toHexString(cp));
            assertTrue(tokens.lexical().contains("a"), "U+" + Integer.toHexString(cp));
            assertFalse(tokens.pinyinComplete(), "无拼音的扩展汉字不能生成残缺拼音分支");
        }
    }

    @Test
    void supplementaryQueryNoLongerCompilesToResidualSingleLetter() {
        Analyzer.QueryTokens tokens = Analyzer.analyzeQuery("𪛖a");
        String metadata = SearchQuery.compileMatch(tokens, SearchScope.METADATA, false);
        String lyrics = SearchQuery.compileMatch(tokens, SearchScope.LYRICS, false);

        assertNotNull(metadata);
        assertNotNull(lyrics);
        assertTrue(metadata.contains("\"𪛖\" AND \"a\""), metadata);
        assertTrue(lyrics.contains("\"𪛖\" AND \"a\""), lyrics);
        assertFalse(metadata.contains("{pinyin_meta_tok} :"), metadata);
        assertFalse(lyrics.contains("{pinyin_lyrics_tok} :"), lyrics);
    }

    @Test
    void supplementaryCjkIsHandledAsUnicodeCodePointsInFuzzyVariants() {
        String first = new String(Character.toChars(0x20000));
        String second = new String(Character.toChars(0x2A6D6));
        String third = new String(Character.toChars(0x2EBF0));

        assertTrue(SearchQuery.cjkVariants(first + second + third, 20)
                .contains(first + third));
        assertTrue(SearchQuery.cjkVariants(first + second + third, 20)
                .contains(second + first + third));
    }

    @Test
    void bruteSupplementaryMixedQueriesAlwaysRetainBothTokenBranches() {
        for (int cp = 0x20000; cp <= 0x2A6DF; cp += 509) {
            String rare = new String(Character.toChars(cp));
            Analyzer.QueryTokens tokens = Analyzer.analyzeQuery(rare + "a");
            String expression = SearchQuery.compileMatch(tokens, SearchScope.METADATA, false);

            assertNotNull(expression, "U+" + Integer.toHexString(cp));
            assertTrue(expression.contains("\"" + rare + "\""), expression);
            assertTrue(expression.contains("\"a\""), expression);
            assertFalse(expression.contains("{pinyin_meta_tok} :"), expression);
        }
    }

    @Test
    void cjkQueriesDoNotUsePositionIndependentPinyinLooseFallback() {
        Analyzer.QueryTokens tokens = Analyzer.analyzeQuery("有个贵族朋友在硬币背后1");
        assertTrue(tokens.hasCjk());
        assertTrue(tokens.hasNonIndexedLetterOrDigit());
        assertTrue(tokens.pinyinComplete());
        assertNull(SearchQuery.compilePinyinLoose(tokens, SearchScope.LYRICS));
    }

    @Test
    void cjkFuzzyVariantsRejectMixedLatinOrDigitQueries() {
        assertEquals(List.of(),
                SearchQuery.cjkVariants("有个贵族朋友在硬币背后1", 20));
        assertEquals(List.of(),
                SearchQuery.cjkVariants("有个贵族朋友在硬币背后a", 20));
    }

    @Test
    void pinyinLooseFallbackRequiresOnlyValidPinyinSyllables() {
        assertNotNull(SearchQuery.compilePinyinLoose(
                Analyzer.analyzeQuery("qing tian"), SearchScope.LYRICS));
        assertNull(SearchQuery.compilePinyinLoose(
                Analyzer.analyzeQuery("qing tian 1"), SearchScope.LYRICS));
        assertNull(SearchQuery.compilePinyinLoose(
                Analyzer.analyzeQuery("hello world"), SearchScope.LYRICS));
    }
}
