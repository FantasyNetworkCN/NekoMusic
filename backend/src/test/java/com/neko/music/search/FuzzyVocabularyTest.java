package com.neko.music.search;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FuzzyVocabularyTest {
    @Test
    void agreesWithFullDamerauMatrixForAllShortWords() {
        List<String> words = new ArrayList<>();
        words.add("");
        for (int length = 1; length <= 5; length++) {
            addWords(words, "", length);
        }
        for (String left : words) {
            for (String right : words) {
                assertEquals(distance(left, right) <= 1, FuzzyVocabulary.withinOne(left, right),
                        () -> left + " / " + right);
            }
        }
    }

    @Test
    void handlesLongQueriesAndUtf16Boundaries() {
        Random random = new Random(20261120);
        String alphabet = "abc音楽\uD840\uDC00";
        for (int length : new int[]{1, 2, 63, 64, 65, 128}) {
            for (int attempt = 0; attempt < 100; attempt++) {
                StringBuilder source = new StringBuilder(length);
                for (int i = 0; i < length; i++) {
                    source.append(alphabet.charAt(random.nextInt(alphabet.length())));
                }
                String left = source.toString();
                int at = random.nextInt(length);
                List<String> variants = List.of(left,
                        left.substring(0, at) + "x" + left.substring(at),
                        left.substring(0, at) + left.substring(at + 1),
                        left.substring(0, at) + "x" + left.substring(at + 1),
                        left.substring(0, at) + "xy" + left.substring(at + 1),
                        at + 1 == length ? left : left.substring(0, at)
                                + left.charAt(at + 1) + left.charAt(at) + left.substring(at + 2));
                for (String right : variants) {
                    boolean expected = distance(left, right) <= 1;
                    assertEquals(expected, FuzzyVocabulary.withinOne(left, right));
                    assertEquals(expected, FuzzyVocabulary.withinOne(right, left));
                }
            }
        }
    }

    private static void addWords(List<String> output, String prefix, int remaining) {
        if (remaining == 0) {
            output.add(prefix);
            return;
        }
        for (char ch : new char[]{'a', 'b', 'c'}) {
            addWords(output, prefix + ch, remaining - 1);
        }
    }

    /** 独立的完整 OSA 距离矩阵作为正确性参照。 */
    private static int distance(String left, String right) {
        int[][] distances = new int[left.length() + 1][right.length() + 1];
        for (int i = 0; i <= left.length(); i++) {
            distances[i][0] = i;
        }
        for (int j = 0; j <= right.length(); j++) {
            distances[0][j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            for (int j = 1; j <= right.length(); j++) {
                distances[i][j] = Math.min(
                        Math.min(distances[i - 1][j] + 1, distances[i][j - 1] + 1),
                        distances[i - 1][j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
                if (i > 1 && j > 1 && left.charAt(i - 1) == right.charAt(j - 2)
                        && left.charAt(i - 2) == right.charAt(j - 1)) {
                    distances[i][j] = Math.min(distances[i][j], distances[i - 2][j - 2] + 1);
                }
            }
        }
        return distances[left.length()][right.length()];
    }
}
