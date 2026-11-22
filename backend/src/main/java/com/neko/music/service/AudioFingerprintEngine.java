/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Portions of the fingerprint and matching logic are derived from Panako 2.1,
 * Copyright (C) 2014-2022 Joren Six / IPEM.
 */
package com.neko.music.service;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 基于 Panako 2.x 的 Gabor 指纹、三点哈希和时间对齐。
 *
 * <p>输入统一为 16 kHz、单声道、signed-16-bit little-endian PCM。
 * 索引仍由 NekoMusic 自己持久化，不引入 Panako 的 LMDB、文件 ID 或 CLI。</p>
 */
public final class AudioFingerprintEngine {
    public static final int SAMPLE_RATE = 16_000;
    public static final int FFT_SIZE = 2_048;
    public static final int HOP_SIZE = PanakoSpectrumExtractor.STEP_SIZE;
    public static final int ALGORITHM_VERSION = 5;

    private static final int HASH_QUERY_RANGE = 2;
    private static final int MAX_VOTE_COMPARISONS = 8_000_000;
    private static final int MAX_INDEX_POSTINGS_PER_HASH = 65_536;
    private static final long MAX_INDEX_TOTAL_POSTINGS = 600_000_000L;
    private static final double SHORT_QUERY_SECONDS = 6d;
    private static final double DEFAULT_ALIGNMENT_TOLERANCE = 2.5d;

    private final TonalFingerprintEngine tonalEngine = new TonalFingerprintEngine();

    public Fingerprint fingerprint(short[] samples) {
        return fingerprint(samples, true);
    }

    /**
     * Indexing path: Panako is fed with the larger block size to reduce overhead.
     */
    public Fingerprint fingerprint(InputStream pcmInput, long maxSamples) throws IOException {
        return fingerprint(pcmInput, maxSamples, false);
    }

    /**
     * Query path: shorter blocks preserve more usable frames for a 3-10 second clip.
     */
    public Fingerprint fingerprintQuery(InputStream pcmInput, long maxSamples) throws IOException {
        return fingerprint(pcmInput, maxSamples, true);
    }

    private Fingerprint fingerprint(InputStream pcmInput, long maxSamples, boolean queryMode)
            throws IOException {
        if (maxSamples < 1) {
            throw new IllegalArgumentException("maxSamples must be positive");
        }
        short[] samples = readPcm(pcmInput, maxSamples);
        return fingerprint(samples, queryMode);
    }

    private Fingerprint fingerprint(short[] samples, boolean queryMode) {
        if (samples == null || samples.length == 0) {
            return new Fingerprint(List.of(), 0);
        }
        List<Landmark> landmarks = new PanakoSpectrumExtractor().extract(samples, queryMode);
        return new Fingerprint(landmarks, samples.length, tonalEngine.extract(samples));
    }

    private static short[] readPcm(InputStream input, long maxSamples) throws IOException {
        BufferedInputStream buffered =
                input instanceof BufferedInputStream ? (BufferedInputStream) input
                        : new BufferedInputStream(input, 32 * 1024);
        ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024);
        byte[] buffer = new byte[16 * 1024];
        long maxBytes = Math.multiplyExact(maxSamples, 2L);
        long total = 0;
        int read;
        while ((read = buffered.read(buffer)) != -1) {
            if (total + read > maxBytes) {
                throw new SampleLimitExceededException(maxSamples);
            }
            output.write(buffer, 0, read);
            total += read;
        }
        if ((total & 1L) != 0) {
            throw new IOException("Odd byte count in PCM stream");
        }
        byte[] bytes = output.toByteArray();
        short[] samples = new short[bytes.length / 2];
        for (int i = 0, offset = 0; i < samples.length; i++, offset += 2) {
            samples[i] = (short) ((bytes[offset] & 0xff) | (bytes[offset + 1] << 8));
        }
        return samples;
    }

    public record Landmark(long hash, int timeFrame, int frequencyBin) {
        public Landmark {
            if (timeFrame < 0) {
                throw new IllegalArgumentException("timeFrame cannot be negative");
            }
            if (frequencyBin < 0) {
                throw new IllegalArgumentException("frequencyBin cannot be negative");
            }
        }

        /** Compatibility constructor for callers that only need time information. */
        public Landmark(long hash, int timeFrame) {
            this(hash, timeFrame, 0);
        }
    }

    public record Fingerprint(
            List<Landmark> landmarks,
            long sampleCount,
            TonalFingerprintEngine.TonalFingerprint tonal) {
        public Fingerprint {
            landmarks = landmarks == null ? List.of() : List.copyOf(landmarks);
            if (sampleCount < 0) {
                throw new IllegalArgumentException("sampleCount cannot be negative");
            }
            if (tonal == null) {
                tonal = TonalFingerprintEngine.TonalFingerprint.empty(sampleCount);
            }
        }

        /** 兼容只包含经典频谱声纹的测试和旧调用方。 */
        public Fingerprint(List<Landmark> landmarks, long sampleCount) {
            this(landmarks, sampleCount, TonalFingerprintEngine.TonalFingerprint.empty(sampleCount));
        }

        public double durationSeconds() {
            return sampleCount / (double) SAMPLE_RATE;
        }
    }

    public record Match(
            int musicId,
            int alignedLandmarks,
            double confidence,
            int offsetFrames,
            int runnerUpLandmarks) {

        public double offsetSeconds() {
            return offsetFrames * HOP_SIZE / (double) SAMPLE_RATE;
        }
    }

    /** Immutable inverted index keyed by a 64-bit Panako hash. */
    public static final class Index {
        private final Map<Long, long[]> postings;
        private final int musicCount;

        private Index(Map<Long, long[]> postings, int musicCount) {
            this.postings = postings;
            this.musicCount = musicCount;
        }

        public static Index build(Map<Integer, Fingerprint> fingerprints) {
            if (fingerprints == null || fingerprints.isEmpty()) {
                return new Index(Map.of(), 0);
            }
            Builder builder = new Builder();
            fingerprints.forEach(builder::add);
            return builder.build();
        }

        public static Builder builder() {
            return new Builder();
        }

        /** Creates a builder that keeps untouched posting arrays from an existing index. */
        public static Builder builder(Index base) {
            return new Builder(base);
        }

        /** Incrementally builds an index without retaining every track fingerprint. */
        public static final class Builder {
            private Map<Long, LongList> mutable = new HashMap<>();
            private final Map<Long, long[]> basePostings;
            private int indexedMusic = 0;
            private long totalPostings = 0L;

            private Builder() {
                basePostings = Map.of();
            }

            private Builder(Index base) {
                basePostings = base == null ? Map.of() : base.postings;
                indexedMusic = base == null ? 0 : base.musicCount;
                for (long[] values : basePostings.values()) {
                    totalPostings += values.length;
                }
            }

            public void add(int musicId, Fingerprint fingerprint) {
                if (musicId <= 0 || fingerprint == null || fingerprint.landmarks().isEmpty()) {
                    return;
                }
                int postingsBeforeTrack = (int) Math.min(Integer.MAX_VALUE, totalPostings);
                Set<Long> uniqueForTrack = new HashSet<>();
                for (Landmark landmark : fingerprint.landmarks()) {
                    if (totalPostings >= MAX_INDEX_TOTAL_POSTINGS) {
                        break;
                    }
                    long duplicateKey = ((landmark.hash() & 0x3f_ffff_ffffL) << 32)
                            | (landmark.timeFrame() & 0xffff_ffffL);
                    if (!uniqueForTrack.add(duplicateKey)) {
                        continue;
                    }
                    long posting = ((long) musicId << 32)
                            | (landmark.timeFrame() & 0xffff_ffffL);
                    LongList values = mutable.computeIfAbsent(
                            landmark.hash(), ignored -> new LongList(basePostings.get(landmark.hash())));
                    if (values.size() >= MAX_INDEX_POSTINGS_PER_HASH) {
                        continue;
                    }
                    values.add(posting);
                    totalPostings++;
                }
                if (totalPostings > postingsBeforeTrack) {
                    indexedMusic++;
                }
            }

            public Index build() {
                Map<Long, long[]> immutable = new HashMap<>(basePostings);
                mutable.forEach((hash, values) -> immutable.put(hash, values.toArray()));
                mutable.clear();
                return new Index(Map.copyOf(immutable), indexedMusic);
            }
        }

        public int musicCount() {
            return musicCount;
        }

        public int uniqueHashCount() {
            return postings.size();
        }

        /** Restores arrays owned by the persistence loader; callers must not mutate them. */
        static Index restore(Map<Long, long[]> persistedPostings, int musicCount) {
            if (musicCount < 0) {
                throw new IllegalArgumentException("musicCount cannot be negative");
            }
            if (persistedPostings == null || persistedPostings.isEmpty()) {
                return new Index(Map.of(), musicCount);
            }
            Map<Long, long[]> restored = new HashMap<>(persistedPostings.size());
            persistedPostings.forEach((hash, values) -> {
                if (values == null || values.length == 0) {
                    throw new IllegalArgumentException("posting list cannot be empty");
                }
                restored.put(hash, values);
            });
            return new Index(Map.copyOf(restored), musicCount);
        }

        /** Package-private read-only view used only by the atomic persistence writer. */
        Map<Long, long[]> postingsView() {
            return postings;
        }

        public Optional<Match> findBest(
                Fingerprint query,
                int minimumAlignedLandmarks,
                double minimumConfidence) {
            if (query == null || query.landmarks().isEmpty() || postings.isEmpty()) {
                return Optional.empty();
            }

            Map<Integer, List<Hit>> hitsByMusic = new HashMap<>();
            Set<String> uniqueQueryLandmarks = new HashSet<>();
            int comparableLandmarks = 0;
            int comparisons = 0;
            int maximumPostingsPerHash = Math.max(512, musicCount * 64);

            for (Landmark queryLandmark : query.landmarks()) {
                String queryKey = queryLandmark.hash() + ":" + queryLandmark.timeFrame();
                if (!uniqueQueryLandmarks.add(queryKey)) {
                    continue;
                }
                boolean foundAny = false;
                for (long hash = queryLandmark.hash() - HASH_QUERY_RANGE;
                     hash <= queryLandmark.hash() + HASH_QUERY_RANGE;
                     hash++) {
                    long[] matches = postings.get(hash);
                    if (matches == null || matches.length == 0
                            || matches.length > maximumPostingsPerHash) {
                        continue;
                    }
                    foundAny = true;
                    for (long posting : matches) {
                        int musicId = (int) (posting >>> 32);
                        int referenceTime = (int) posting;
                        hitsByMusic.computeIfAbsent(musicId, ignored -> new ArrayList<>())
                                .add(new Hit(queryLandmark.timeFrame(), referenceTime));
                        comparisons++;
                        if (comparisons >= MAX_VOTE_COMPARISONS) {
                            break;
                        }
                    }
                    if (comparisons >= MAX_VOTE_COMPARISONS) {
                        break;
                    }
                }
                if (foundAny) {
                    comparableLandmarks++;
                }
                if (comparisons >= MAX_VOTE_COMPARISONS) {
                    break;
                }
            }
            if (hitsByMusic.isEmpty() || comparableLandmarks == 0) {
                return Optional.empty();
            }

            boolean shortQuery = query.durationSeconds() < SHORT_QUERY_SECONDS;
            int minimumHits = shortQuery
                    ? 3
                    : Math.max(3, minimumAlignedLandmarks);
            double effectiveConfidence = shortQuery
                    ? Math.min(minimumConfidence, 0.03d)
                    : minimumConfidence;
            List<Candidate> candidates = new ArrayList<>();

            for (Map.Entry<Integer, List<Hit>> entry : hitsByMusic.entrySet()) {
                List<Hit> hits = entry.getValue();
                if (hits.size() < minimumHits) {
                    continue;
                }
                hits.sort(Comparator.comparingInt(Hit::queryTime));
                Alignment alignment = estimateAlignment(hits);
                int aligned = 0;
                int firstQuery = Integer.MAX_VALUE;
                int lastQuery = Integer.MIN_VALUE;
                for (Hit hit : hits) {
                    double predicted = alignment.slope() * hit.queryTime()
                            + alignment.intercept();
                    if (Math.abs(hit.deltaTime() - predicted)
                            <= DEFAULT_ALIGNMENT_TOLERANCE) {
                        aligned++;
                        firstQuery = Math.min(firstQuery, hit.queryTime());
                        lastQuery = Math.max(lastQuery, hit.queryTime());
                    }
                }
                if (aligned < minimumHits) {
                    continue;
                }
                double alignedSeconds = Math.max(
                        0d, (lastQuery - firstQuery) * HOP_SIZE / (double) SAMPLE_RATE);
                if (alignedSeconds < 1d) {
                    continue;
                }
                double confidence = Math.min(
                        1d, aligned / (double) comparableLandmarks);
                if (confidence < effectiveConfidence) {
                    continue;
                }
                candidates.add(new Candidate(
                        entry.getKey(), aligned, confidence,
                        (int) Math.round(alignment.intercept())));
            }
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            candidates.sort(Comparator.comparingInt(Candidate::aligned).reversed());
            Candidate best = candidates.get(0);
            int runnerUp = candidates.size() > 1 ? candidates.get(1).aligned() : 0;
            int requiredGap = shortQuery ? 1 : 3;
            boolean distinct = runnerUp == 0
                    || best.aligned() >= runnerUp + requiredGap
                    || best.aligned() >= Math.ceil(runnerUp * 1.10d);
            if (!distinct) {
                return Optional.empty();
            }
            return Optional.of(new Match(
                    best.musicId(),
                    best.aligned(),
                    best.confidence(),
                    Math.max(0, best.offsetFrames()),
                    runnerUp));
        }

        private static Alignment estimateAlignment(List<Hit> hits) {
            double slope = 0d;
            double intercept = medianDelta(hits);
            if (hits.size() >= 4) {
                int part = Math.max(2, hits.size() / 5);
                List<Hit> first = hits.subList(0, part);
                List<Hit> last = hits.subList(hits.size() - part, hits.size());
                double x1 = averageQueryTime(first);
                double x2 = averageQueryTime(last);
                double y1 = medianDelta(first);
                double y2 = medianDelta(last);
                if (Math.abs(x2 - x1) > 1d) {
                    slope = (y2 - y1) / (x2 - x1);
                    if (!Double.isFinite(slope) || Math.abs(slope) > 0.35d) {
                        slope = 0d;
                    }
                }
            }
            double[] residuals = new double[hits.size()];
            for (int i = 0; i < hits.size(); i++) {
                Hit hit = hits.get(i);
                residuals[i] = hit.deltaTime() - slope * hit.queryTime();
            }
            intercept = median(residuals);
            return new Alignment(slope, intercept);
        }

        private static double averageQueryTime(List<Hit> hits) {
            double sum = 0d;
            for (Hit hit : hits) {
                sum += hit.queryTime();
            }
            return sum / hits.size();
        }

        private static double medianDelta(List<Hit> hits) {
            double[] values = new double[hits.size()];
            for (int i = 0; i < hits.size(); i++) {
                values[i] = hits.get(i).deltaTime();
            }
            return median(values);
        }

        private static double median(double[] values) {
            Arrays.sort(values);
            int middle = values.length / 2;
            return values.length % 2 == 0
                    ? (values[middle - 1] + values[middle]) / 2d
                    : values[middle];
        }

        private record Hit(int queryTime, int referenceTime) {
            private int deltaTime() {
                return referenceTime - queryTime;
            }
        }

        private record Alignment(double slope, double intercept) {
        }

        private record Candidate(
                int musicId,
                int aligned,
                double confidence,
                int offsetFrames) {
        }
    }

    public static final class SampleLimitExceededException extends IOException {
        public SampleLimitExceededException(long maxSamples) {
            super("PCM sample limit exceeded: " + maxSamples);
        }
    }

    private static final class LongList {
        private long[] values = new long[16];
        private int size;

        private LongList(long[] initial) {
            if (initial != null && initial.length > 0) {
                values = Arrays.copyOf(initial, Math.max(16, initial.length));
                size = initial.length;
            }
        }

        private int size() {
            return size;
        }

        private void add(long value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[size++] = value;
        }

        private long[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}
