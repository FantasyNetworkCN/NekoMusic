package com.neko.music.service;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaylistTextNormalizationTest {
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    @Test
    void preservesAllUnicodeCategoriesAndSupplementaryCodePoints() {
        // 分组覆盖整个 Unicode 范围，涵盖全部数字分类、未分配字符和代理项。
        for (int first = 0; first <= Character.MAX_CODE_POINT; first += 1024) {
            StringBuilder sample = new StringBuilder();
            for (int cp = first; cp <= Math.min(Character.MAX_CODE_POINT, first + 1023); cp++) {
                sample.appendCodePoint(cp).append('/');
            }
            assertSameNormalization(sample.toString());
        }
    }

    @Test
    void preservesContextualCaseConversionSeparatorsAndMalformedSurrogates() {
        for (String input : new String[]{
                "", "  /---", "ONE__two  three", "İ ΑΣ ΟΣ σς Iı",
                "Ⅷ ² 𠀀 𐐀", "\uD800x\uDC00", "你好\u0301世界", "foo\tbar\nbaz",
                "周杰伦---晴天 🎵 rock/live", "a\u00A0b\u200Bc"
        }) {
            assertSameNormalization(input);
        }
        Random random = new Random(20261121);
        for (int i = 0; i < 10000; i++) {
            StringBuilder sample = new StringBuilder("İ ΑΣ / ");
            for (int j = 0; j < 16; j++) {
                sample.appendCodePoint(random.nextInt(Character.MAX_CODE_POINT + 1));
            }
            assertSameNormalization(sample.toString());
        }
    }

    private static void assertSameNormalization(String input) {
        String expected = WHITESPACE.matcher(NON_WORD.matcher(input.toLowerCase(Locale.ROOT))
                .replaceAll(" ")).replaceAll(" ").trim();
        assertEquals(expected, PlaylistRecommendationRanker.normalizeText(input));
    }
}
