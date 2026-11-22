/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Portions of the fingerprint and matching logic are derived from Panako 2.1,
 * Copyright (C) 2014-2022 Joren Six / IPEM.
 */
package com.neko.music.service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
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

    private static final int MAX_INDEX_POSTINGS_PER_HASH = 65_536;
    private static final long MAX_INDEX_TOTAL_POSTINGS = 600_000_000L;

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

    static short[] readPcm(InputStream input, long maxSamples) throws IOException {
        BufferedInputStream buffered =
                input instanceof BufferedInputStream ? (BufferedInputStream) input
                        : new BufferedInputStream(input, 32 * 1024);
        int maximumCapacity = (int) Math.min(maxSamples, Integer.MAX_VALUE - 8L);
        short[] samples = new short[Math.min(16 * 1024, maximumCapacity)];
        byte[] buffer = new byte[16 * 1024];
        long maxBytes = Math.multiplyExact(maxSamples, 2L);
        long total = 0;
        int size = 0;
        int pending = -1;
        int read;
        while ((read = buffered.read(buffer)) != -1) {
            if (total + read > maxBytes) {
                throw new SampleLimitExceededException(maxSamples);
            }
            total += read;
            int added = (read + (pending < 0 ? 0 : 1)) / 2;
            long required = (long) size + added;
            if (required > maximumCapacity) {
                throw new SampleLimitExceededException(maxSamples);
            }
            if (required > samples.length) {
                int capacity = (int) Math.min(maximumCapacity,
                        Math.max(required, samples.length * 2L));
                samples = Arrays.copyOf(samples, capacity);
            }
            int offset = 0;
            if (pending >= 0 && read > 0) {
                samples[size++] = (short) (pending | (buffer[offset++] << 8));
                pending = -1;
            }
            while (offset + 1 < read) {
                samples[size++] = (short) ((buffer[offset] & 0xff) | (buffer[offset + 1] << 8));
                offset += 2;
            }
            if (offset < read) {
                pending = buffer[offset] & 0xff;
            }
        }
        if (pending >= 0) {
            throw new IOException("Odd byte count in PCM stream");
        }
        return size == samples.length ? samples : Arrays.copyOf(samples, size);
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

        /** 仅时间定位的特征点，频段信息留空。 */
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

        /** 创建仅含经典频谱特征的查询。 */
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
        private final RustFingerprintIndex postings;
        private final int musicCount;

        private Index(Map<Long, long[]> postings, int musicCount) {
            this.postings = postings instanceof RustFingerprintIndex nativeIndex ? nativeIndex
                    : RustFingerprintIndex.create(postings, null);
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
            private final FingerprintPostings basePostings;
            private int indexedMusic = 0;
            private long totalPostings = 0L;

            private Builder() {
                basePostings = FingerprintPostings.empty();
            }

            private Builder(Index base) {
                basePostings = base == null ? FingerprintPostings.empty() : base.postings;
                indexedMusic = base == null ? 0 : base.musicCount;
                totalPostings = basePostings.postingCount();
            }

            public void add(int musicId, Fingerprint fingerprint) {
                if (musicId <= 0 || fingerprint == null || fingerprint.landmarks().isEmpty()) {
                    return;
                }
                try (TrackAppender appender = beginTrack(musicId, true)) {
                    for (Landmark landmark : fingerprint.landmarks()) {
                        appender.add(landmark.hash(), landmark.timeFrame());
                    }
                }
            }

            /**
             * 开始向索引追加一首曲目。调用方可以逐条写入 landmark，不必先把整首歌物化成
             * {@link Landmark} 对象列表——50k 曲库全量重建时那会产生数 GB 的一次性垃圾。
             *
             * <p>{@code deduplicate} 关闭时不做 (hash,timeFrame) 去重，适合调用方已经保证
             * 唯一、希望完全零对象的场景。</p>
             */
            public TrackAppender beginTrack(int musicId, boolean deduplicate) {
                return new TrackAppender(musicId, deduplicate);
            }

            /** 一首曲目的追加窗口；{@link #close()} 时按是否真的写入过 postings 更新曲目数。 */
            public final class TrackAppender implements AutoCloseable {
                private final int musicId;
                private final Set<Long> uniqueForTrack;
                private final long postingsBeforeTrack;
                private boolean closed;

                private TrackAppender(int musicId, boolean deduplicate) {
                    this.musicId = musicId;
                    this.uniqueForTrack = deduplicate ? new HashSet<>() : null;
                    this.postingsBeforeTrack = totalPostings;
                }

                /** @return true 表示该 landmark 被写入了倒排表。 */
                public boolean add(long hash, int timeFrame) {
                    if (closed || musicId <= 0 || totalPostings >= MAX_INDEX_TOTAL_POSTINGS) {
                        return false;
                    }
                    if (uniqueForTrack != null) {
                        long duplicateKey = ((hash & 0x3f_ffff_ffffL) << 32)
                                | (timeFrame & 0xffff_ffffL);
                        if (!uniqueForTrack.add(duplicateKey)) {
                            return false;
                        }
                    }
                    LongList values = mutable.computeIfAbsent(
                            hash, ignored -> new LongList(basePostings.get(hash)));
                    if (values.size() >= MAX_INDEX_POSTINGS_PER_HASH) {
                        return false;
                    }
                    values.add(((long) musicId << 32) | (timeFrame & 0xffff_ffffL));
                    totalPostings++;
                    return true;
                }

                @Override
                public void close() {
                    if (!closed && totalPostings > postingsBeforeTrack) {
                        indexedMusic++;
                    }
                    closed = true;
                }
            }

            public Index build() {
                Map<Long, long[]> additions = new HashMap<>();
                mutable.forEach((hash, values) -> additions.put(hash, values.toArray()));
                mutable.clear();
                if (basePostings.isEmpty()) {
                    return new Index(additions, indexedMusic);
                }
                return new Index(RustFingerprintIndex.create(additions,
                        (RustFingerprintIndex) basePostings), indexedMusic);
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
            // 紧凑表复用加载器的 posting 数组，仅将键转成原始 long。
            return new Index(persistedPostings, musicCount);
        }

        /** Package-private read-only view used only by the atomic persistence writer. */
        Map<Long, long[]> postingsView() {
            return postings;
        }

        /** 原生索引实际持有的字节数（包含共享基础层），供离线资源核对。 */
        long nativeBytes() {
            return postings.nativeBytes();
        }

        boolean usesRust() {
            return true;
        }

        public Optional<Match> findBest(
                Fingerprint query,
                int minimumAlignedLandmarks,
                double minimumConfidence) {
            if (query == null || query.landmarks().isEmpty() || postings.isEmpty()) {
                return Optional.empty();
            }
            return postings.findBest(query, musicCount, minimumAlignedLandmarks, minimumConfidence);
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
