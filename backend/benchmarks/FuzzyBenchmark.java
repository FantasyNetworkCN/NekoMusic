package com.neko.music.search;

import com.neko.music.benchmark.BenchmarkMeter;

import java.util.Random;

/** 只测模糊词典的候选距离判定；包含相等、插入、删除、替换、换位和不匹配。 */
public final class FuzzyBenchmark {
    private static volatile int result;

    public static void main(String[] arguments) {
        String[] left = new String[4096];
        String[] right = new String[left.length];
        Random random = new Random(20261120);
        for (int i = 0; i < left.length; i++) {
            StringBuilder word = new StringBuilder();
            for (int j = 0, length = 4 + random.nextInt(12); j < length; j++) {
                word.append((char) ('a' + random.nextInt(26)));
            }
            left[i] = word.toString();
            int at = random.nextInt(word.length() - 1);
            right[i] = switch (i % 6) {
                case 0 -> left[i];
                case 1 -> word.substring(0, at) + "x" + word.substring(at);
                case 2 -> word.substring(0, at) + word.substring(at + 1);
                case 3 -> word.substring(0, at) + "x" + word.substring(at + 1);
                case 4 -> word.substring(0, at) + word.charAt(at + 1) + word.charAt(at)
                        + word.substring(at + 2);
                default -> "zzzz" + word.substring(4);
            };
        }
        BenchmarkMeter.measure("fuzzy_candidate", left.length, 100, 200, () -> {
            int matches = 0;
            for (int i = 0; i < left.length; i++) {
                if (FuzzyVocabulary.withinOne(left[i], right[i])) {
                    matches++;
                }
            }
            result = matches;
        });
        System.out.println("fuzzy_matches=" + result);
    }
}
