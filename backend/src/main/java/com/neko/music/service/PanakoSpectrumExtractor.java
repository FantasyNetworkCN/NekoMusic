/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Portions derived from Panako 2.1, Copyright (C) 2014-2022 Joren Six / IPEM.
 */
package com.neko.music.service;

import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.io.TarsosDSPAudioFormat;
import be.ugent.jgaborator.JGaborator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Panako 2.x 的声音前端：Gabor 变换、二维峰值检测和三点指纹构造。
 *
 * <p>这里只移植 Panako 的算法内核，不使用其 CLI、文件 ID、LMDB 和配置单例。
 * 索引与查询都由 NekoMusic 的 {@link AudioFingerprintEngine} 管理。</p>
 */
final class PanakoSpectrumExtractor {
    static final int SAMPLE_RATE = 16_000;
    static final int STEP_SIZE = 128;

    private static final int BANDS_PER_OCTAVE = 85;
    private static final double MIN_FREQUENCY = 110d;
    private static final double MAX_FREQUENCY = 7_040d;
    private static final double REFERENCE_FREQUENCY = 440d;
    private static final int TIME_MAX_FILTER_SIZE = 25;
    private static final int FREQ_MAX_FILTER_SIZE = 103;
    private static final int FP_MIN_FREQ_DIST = 1;
    private static final int FP_MAX_FREQ_DIST = 128;
    private static final int FP_MIN_TIME_DIST = 2;
    private static final int FP_MAX_TIME_DIST = 33;
    private static final int INDEX_BLOCK_SIZE = 8_192;
    private static final int QUERY_BLOCK_SIZE = 2_048;
    private static final int INDEX_CHUNK_SECONDS = 120;
    private static final int INDEX_CHUNK_OVERLAP_SECONDS = 20;
    private static final int TAIL_PAD_SECONDS = 2;

    List<AudioFingerprintEngine.Landmark> extract(short[] samples, boolean queryMode) {
        if (samples == null || samples.length == 0) {
            return List.of();
        }

        int chunkSamples = queryMode
                ? samples.length
                : INDEX_CHUNK_SECONDS * SAMPLE_RATE;
        int overlapSamples = queryMode
                ? 0
                : INDEX_CHUNK_OVERLAP_SECONDS * SAMPLE_RATE;
        int stepSamples = Math.max(1, chunkSamples - overlapSamples);
        Set<AudioFingerprintEngine.Landmark> unique = new HashSet<>();
        int start = 0;
        while (start < samples.length) {
            int end = Math.min(samples.length, start + chunkSamples);
            short[] chunk = Arrays.copyOfRange(samples, start, end);
            unique.addAll(extractChunk(chunk, start, queryMode));
            if (end == samples.length) {
                break;
            }
            start += stepSamples;
        }
        List<AudioFingerprintEngine.Landmark> result = new ArrayList<>(unique);
        result.sort(Comparator
                .comparingInt(AudioFingerprintEngine.Landmark::timeFrame)
                .thenComparingLong(AudioFingerprintEngine.Landmark::hash)
                .thenComparingInt(AudioFingerprintEngine.Landmark::frequencyBin));
        return List.copyOf(result);
    }

    private List<AudioFingerprintEngine.Landmark> extractChunk(
            short[] samples, int chunkStartSample, boolean queryMode) {
        int blockSize = queryMode ? QUERY_BLOCK_SIZE : INDEX_BLOCK_SIZE;
        JGaborator gaborator = new JGaborator(
                blockSize,
                SAMPLE_RATE,
                BANDS_PER_OCTAVE,
                MIN_FREQUENCY,
                MAX_FREQUENCY,
                REFERENCE_FREQUENCY,
                STEP_SIZE);
        AudioEvent event = new AudioEvent(
                new TarsosDSPAudioFormat(SAMPLE_RATE, 16, 1, true, false));
        float[] block = new float[blockSize];

        int position = 0;
        while (position < samples.length) {
            Arrays.fill(block, 0f);
            int copied = Math.min(blockSize, samples.length - position);
            for (int i = 0; i < copied; i++) {
                block[i] = samples[position + i] / 32768f;
            }
            event.setFloatBuffer(block.clone());
            gaborator.process(event);
            position += blockSize;
        }

        // Panako 的固定系数只会在后续 block 中写入历史；补静音让尾部系数落盘。
        int paddedSamples = TAIL_PAD_SECONDS * SAMPLE_RATE;
        for (int padded = 0; padded < paddedSamples; padded += blockSize) {
            Arrays.fill(block, 0f);
            event.setFloatBuffer(block.clone());
            gaborator.process(event);
        }
        gaborator.processingFinished();

        List<float[]> coefficients = gaborator.getCoefficents();
        if (coefficients.isEmpty()) {
            return List.of();
        }

        int bandCount = 0;
        for (float[] frame : coefficients) {
            bandCount = Math.max(bandCount, frame == null ? 0 : frame.length);
        }
        if (bandCount < 3) {
            return List.of();
        }

        List<EventPoint> eventPoints = findEventPoints(coefficients, bandCount);
        int originalFrames = (samples.length + STEP_SIZE - 1) / STEP_SIZE;
        int frameOffset = chunkStartSample / STEP_SIZE;
        return packFingerprints(eventPoints, originalFrames, frameOffset);
    }

    private List<EventPoint> findEventPoints(List<float[]> coefficients, int bandCount) {
        Map<Integer, float[]> previousMagnitudes = new HashMap<>();
        Map<Integer, float[]> previousMaxMagnitudes = new HashMap<>();
        List<EventPoint> eventPoints = new ArrayList<>();
        float[] maxHorizontal = new float[bandCount];
        int analysisFrameIndex = 0;

        for (float[] currentMagnitudes : coefficients) {
            int currentBandCount = Math.min(bandCount, currentMagnitudes.length);
            float[] magnitudes = Arrays.copyOf(currentMagnitudes, bandCount);
            float[] maxMagnitudes = new float[bandCount];
            maxFilterClamped(magnitudes, maxMagnitudes, FREQ_MAX_FILTER_SIZE);

            previousMagnitudes.put(analysisFrameIndex, magnitudes);
            previousMaxMagnitudes.put(analysisFrameIndex, maxMagnitudes);

            if (previousMaxMagnitudes.size() == TIME_MAX_FILTER_SIZE) {
                int centerFrame = analysisFrameIndex - TIME_MAX_FILTER_SIZE / 2;
                float[] maxFrame = previousMaxMagnitudes.get(centerFrame);
                float[] frameMagnitudes = previousMagnitudes.get(centerFrame);
                if (maxFrame == null || frameMagnitudes == null) {
                    analysisFrameIndex++;
                    continue;
                }

                for (int frequency = 2; frequency < currentBandCount - 1; frequency++) {
                    float current = frameMagnitudes[frequency];
                    if (current != 0f && maxFrame[frequency] == current) {
                        horizontalFilter(
                                frequency,
                                centerFrame,
                                previousMaxMagnitudes,
                                maxHorizontal);
                        if (current == maxHorizontal[frequency]) {
                            float[] previous = previousMagnitudes.get(centerFrame - 1);
                            float[] next = previousMagnitudes.get(centerFrame + 1);
                            if (previous == null || next == null) {
                                continue;
                            }
                            float totalMagnitude =
                                    sum9(
                                            frameMagnitudes, previous, next,
                                            frequency);
                            eventPoints.add(new EventPoint(centerFrame, frequency, totalMagnitude));
                        }
                    }
                }

                previousMaxMagnitudes.remove(
                        analysisFrameIndex - TIME_MAX_FILTER_SIZE + 1);
                previousMagnitudes.remove(
                        analysisFrameIndex - TIME_MAX_FILTER_SIZE + 1);
            }
            analysisFrameIndex++;
        }
        return eventPoints;
    }

    private static void horizontalFilter(
            int frequency,
            int centerFrame,
            Map<Integer, float[]> previousMaxMagnitudes,
            float[] maxHorizontal) {
        maxHorizontal[frequency] = -1000f;
        int half = TIME_MAX_FILTER_SIZE / 2;
        for (int frame = centerFrame - half; frame < centerFrame + half; frame++) {
            float[] values = previousMaxMagnitudes.get(frame);
            if (values != null && frequency < values.length) {
                maxHorizontal[frequency] = Math.max(
                        maxHorizontal[frequency], values[frequency]);
            }
        }
    }

    private static float sum9(
            float[] current,
            float[] previous,
            float[] next,
            int frequency) {
        return current[frequency] + previous[frequency] + next[frequency]
                + current[frequency + 1] + previous[frequency + 1] + next[frequency + 1]
                + current[frequency - 1] + previous[frequency - 1] + next[frequency - 1];
    }

    private List<AudioFingerprintEngine.Landmark> packFingerprints(
            List<EventPoint> eventPoints,
            int originalFrames,
            int frameOffset) {
        List<AudioFingerprintEngine.Landmark> fingerprints = new ArrayList<>();
        Set<AudioFingerprintEngine.Landmark> unique = new HashSet<>();
        for (int i = 0; i < eventPoints.size(); i++) {
            EventPoint first = eventPoints.get(i);
            if (first.time() >= originalFrames) {
                break;
            }
            if (first.time() < 0) {
                continue;
            }
            for (int j = i + 1; j < eventPoints.size(); j++) {
                EventPoint second = eventPoints.get(j);
                int timeDistance = second.time() - first.time();
                if (timeDistance > FP_MAX_TIME_DIST) {
                    break;
                }
                if (second.time() >= originalFrames) {
                    break;
                }
                if (timeDistance < FP_MIN_TIME_DIST
                        || Math.abs(first.frequency() - second.frequency())
                        < FP_MIN_FREQ_DIST
                        || Math.abs(first.frequency() - second.frequency())
                        > FP_MAX_FREQ_DIST) {
                    continue;
                }
                for (int k = j + 1; k < eventPoints.size(); k++) {
                    EventPoint third = eventPoints.get(k);
                    timeDistance = third.time() - second.time();
                    if (timeDistance > FP_MAX_TIME_DIST) {
                        break;
                    }
                    if (third.time() >= originalFrames) {
                        break;
                    }
                    if (timeDistance < FP_MIN_TIME_DIST
                            || Math.abs(second.frequency() - third.frequency())
                            < FP_MIN_FREQ_DIST
                            || Math.abs(second.frequency() - third.frequency())
                            > FP_MAX_FREQ_DIST) {
                        continue;
                    }
                    long hash = hash(first, second, third);
                    if (hash == 0L) {
                        continue;
                    }
                    AudioFingerprintEngine.Landmark landmark =
                            new AudioFingerprintEngine.Landmark(
                                    hash,
                                    frameOffset + first.time(),
                                    first.frequency());
                    if (unique.add(landmark)) {
                        fingerprints.add(landmark);
                    }
                }
            }
        }
        return fingerprints;
    }

    /**
     * Panako 2.1 的近似哈希。时间比例放在低位，查询时允许 ±2 的邻近范围。
     */
    private static long hash(EventPoint first, EventPoint second, EventPoint third) {
        int t1 = first.time();
        int f1 = first.frequency();
        float m1 = first.magnitude();
        int t2 = second.time();
        int f2 = second.frequency();
        float m2 = second.magnitude();
        int t3 = third.time();
        int f3 = third.frequency();
        float m3 = third.magnitude();

        long f1GreaterThanF2 = f1 > f2 ? 1 : 0;
        long f2GreaterThanF3 = f2 > f3 ? 1 : 0;
        long f3GreaterThanF1 = f3 > f1 ? 1 : 0;
        long m1GreaterThanM2 = m1 > m2 ? 1 : 0;
        long m2GreaterThanM3 = m2 > m3 ? 1 : 0;
        long m3GreaterThanM1 = m3 > m1 ? 1 : 0;
        long t1t2GreaterThanT3t2 = (t2 - t1) > (t3 - t2) ? 1 : 0;
        long f1f2GreaterThanF3f2 =
                Math.abs(f2 - f1) > Math.abs(f3 - f2) ? 1 : 0;

        long f1Range = f1 >> 5;
        long df2f1 = Math.abs(f2 - f1) >> 2;
        long df3f2 = Math.abs(f3 - f2) >> 2;
        long ratioT = (long) ((t2 - t1) / (float) (t3 - t1) * 64);

        return ((ratioT & 63L) << 0)
                | ((f1GreaterThanF2 & 1L) << 6)
                | ((f2GreaterThanF3 & 1L) << 7)
                | ((f3GreaterThanF1 & 1L) << 8)
                | ((m1GreaterThanM2 & 1L) << 9)
                | ((m2GreaterThanM3 & 1L) << 10)
                | ((m3GreaterThanM1 & 1L) << 11)
                | ((t1t2GreaterThanT3t2 & 1L) << 12)
                | ((f1f2GreaterThanF3f2 & 1L) << 13)
                | ((f1Range & 255L) << 14)
                | ((df2f1 & 63L) << 22)
                | ((df3f2 & 63L) << 28);
    }

    private static void maxFilterClamped(
            float[] input, float[] output, int windowSize) {
        int half = windowSize / 2;
        float[] padded = new float[input.length + windowSize - 1];
        Arrays.fill(padded, 0, half, input[0]);
        System.arraycopy(input, 0, padded, half, input.length);
        Arrays.fill(
                padded,
                half + input.length,
                padded.length,
                input[input.length - 1]);

        ArrayDeque<Integer> deque = new ArrayDeque<>(windowSize);
        for (int i = 0; i < padded.length; i++) {
            while (!deque.isEmpty()
                    && padded[deque.peekLast()] <= padded[i]) {
                deque.removeLast();
            }
            deque.addLast(i);
            if (deque.peekFirst() <= i - windowSize) {
                deque.removeFirst();
            }
            if (i >= windowSize - 1) {
                int outputIndex = i - windowSize + 1;
                if (outputIndex < output.length) {
                    output[outputIndex] = padded[deque.peekFirst()];
                }
            }
        }
    }

    private record EventPoint(int time, int frequency, float magnitude) {
    }
}
