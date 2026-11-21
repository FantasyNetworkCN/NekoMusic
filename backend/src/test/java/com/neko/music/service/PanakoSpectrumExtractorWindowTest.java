package com.neko.music.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PanakoSpectrumExtractorWindowTest {
    @Test
    void reusableFrequencyFilterPreservesClampingTiesAndSpecialValues() throws Exception {
        Method filter = PanakoSpectrumExtractor.class.getDeclaredMethod("maxFilterClamped",
                float[].class, float[].class, int.class, float[].class, int[].class);
        filter.setAccessible(true);
        Random random = new Random(20261121);
        for (int bands : new int[]{1, 2, 9, 103, 512}) {
            float[] padded = new float[bands + 102];
            int[] deque = new int[padded.length];
            float[] actual = new float[bands];
            for (int sample = 0; sample < 100; sample++) {
                float[] input = new float[bands];
                for (int i = 0; i < bands; i++) {
                    input[i] = switch (random.nextInt(12)) {
                        case 0 -> Float.NaN;
                        case 1 -> Float.POSITIVE_INFINITY;
                        case 2 -> Float.NEGATIVE_INFINITY;
                        case 3 -> -0f;
                        case 4 -> 0f;
                        default -> random.nextInt(8) / 4f;
                    };
                }
                filter.invoke(null, input, actual, 103, padded, deque);
                assertArrayEquals(legacyFilter(input), actual);
            }
        }
    }

    @Test
    void circularTimeWindowPreservesEveryEventIncludingWarmupAndTail() throws Exception {
        Method find = PanakoSpectrumExtractor.class.getDeclaredMethod("findEventPoints", List.class, int.class);
        find.setAccessible(true);
        Random random = new Random(7);
        for (int count : new int[]{0, 1, 24, 25, 26, 50, 127, 256}) {
            List<float[]> frames = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                // 不等长频带验证清零补齐，重复幅度验证相等极值的保留顺序。
                float[] frame = new float[3 + random.nextInt(46)];
                for (int j = 0; j < frame.length; j++) {
                    frame[j] = random.nextInt(8) == 0 ? random.nextFloat() * 3 : 0;
                }
                frames.add(frame);
            }
            @SuppressWarnings("unchecked")
            List<Object> actual = (List<Object>) find.invoke(new PanakoSpectrumExtractor(), frames, 48);
            assertEquals(legacyEvents(frames, 48), actual.stream().map(Object::toString).toList());
        }
    }

    /** 改动前的队列滤波作为回归参考。 */
    private static float[] legacyFilter(float[] input) {
        int window = 103;
        float[] padded = new float[input.length + window - 1];
        Arrays.fill(padded, 0, window / 2, input[0]);
        System.arraycopy(input, 0, padded, window / 2, input.length);
        Arrays.fill(padded, window / 2 + input.length, padded.length, input[input.length - 1]);
        float[] result = new float[input.length];
        ArrayDeque<Integer> deque = new ArrayDeque<>();
        for (int i = 0; i < padded.length; i++) {
            while (!deque.isEmpty() && padded[deque.peekLast()] <= padded[i]) {
                deque.removeLast();
            }
            deque.addLast(i);
            if (deque.peekFirst() <= i - window) {
                deque.removeFirst();
            }
            if (i >= window - 1) {
                result[i - window + 1] = padded[deque.peekFirst()];
            }
        }
        return result;
    }

    /** 改动前的 Map 时间窗，保留原浮点求和顺序和首尾边界。 */
    private static List<String> legacyEvents(List<float[]> frames, int bands) {
        Map<Integer, float[]> magnitudes = new HashMap<>();
        Map<Integer, float[]> maxima = new HashMap<>();
        List<String> events = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            float[] current = Arrays.copyOf(frames.get(i), bands);
            magnitudes.put(i, current);
            maxima.put(i, legacyFilter(current));
            if (maxima.size() == 25) {
                int center = i - 12;
                float[] values = magnitudes.get(center);
                for (int frequency = 2; frequency < Math.min(bands, frames.get(i).length) - 1; frequency++) {
                    float value = values[frequency];
                    if (value != 0f && maxima.get(center)[frequency] == value) {
                        float maximum = -1000f;
                        for (int frame = center - 12; frame < center + 12; frame++) {
                            float[] previous = maxima.get(frame);
                            if (previous != null) {
                                maximum = Math.max(maximum, previous[frequency]);
                            }
                        }
                        if (value == maximum) {
                            float[] previous = magnitudes.get(center - 1);
                            float[] next = magnitudes.get(center + 1);
                            float sum = values[frequency] + previous[frequency] + next[frequency]
                                    + values[frequency + 1] + previous[frequency + 1] + next[frequency + 1]
                                    + values[frequency - 1] + previous[frequency - 1] + next[frequency - 1];
                            events.add("EventPoint[time=" + center + ", frequency=" + frequency
                                    + ", magnitude=" + sum + "]");
                        }
                    }
                }
                maxima.remove(i - 24);
                magnitudes.remove(i - 24);
            }
        }
        return events;
    }
}
