package com.neko.music.service;

import be.tarsos.dsp.util.fft.FFT;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 面向翻唱、重混和不同编曲版本的和声/旋律声纹。
 *
 * <p>Panako 指纹擅长同一录音的变调变速识别，但不同混音会改变大量频谱峰值。
 * 这里从同一段 16 kHz PCM 中提取每秒两帧的色度特征，并建立两类与绝对音高无关的
 * 倒排特征：和弦进行与主音高轮廓。查询时先用它们召回候选，再用完整色度序列做
 * 转调与轻微变速校验，避免仅凭几个常见和弦就误报。</p>
 */
final class TonalFingerprintEngine {
    static final int FRAME_RATE = 2;
    static final int FRAME_SAMPLES = AudioFingerprintEngine.SAMPLE_RATE / FRAME_RATE;
    static final int ALGORITHM_VERSION = 1;

    private static final int FFT_SIZE = 4_096;
    private static final int WINDOW_STEP = FFT_SIZE / 2;
    private static final double MIN_FREQUENCY = 55d;
    private static final double MAX_FREQUENCY = 5_000d;
    private static final double MIN_CHROMA_ENERGY = 1e-7d;
    private static final double MIN_CHORD_SCORE = 0.55d;
    private static final int CHORD_GRAM = 3;
    private static final int PITCH_GRAM = 3;
    private static final int MAX_COMPARISONS = 8_000_000;
    private static final int MAX_CANDIDATES = 128;
    private static final int MAX_VERIFICATIONS = 96;
    private static final int MIN_QUERY_CHORD_TOKENS = 4;
    private static final double MIN_QUERY_CHORD_RATE = 0.2d;
    private static final double MIN_CHORD_VOTE_RATE = 0.25d;
    private static final double MIN_PITCH_VOTE_RATE = 0.55d;
    private static final double MIN_CHORD_AGREEMENT = 0.55d;
    private static final double MIN_CHROMA_SIMILARITY = 0.80d;
    private static final double MIN_SHORT_CHROMA_SIMILARITY = 0.85d;
    private static final double SHORT_QUERY_SECONDS = 6d;
    private static final long MAX_INDEX_TOTAL_POSTINGS = 160_000_000L;
    private static final byte UNKNOWN = (byte) 0xff;
    private static final double[] MAJOR_TEMPLATE = {1, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0};
    private static final double[] MINOR_TEMPLATE = {1, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0};
    private static final double[] TEMPO_SCALES = {1d, 0.98d, 1.02d, 0.96d, 1.04d, 0.94d, 1.06d};

    TonalFingerprint extract(short[] samples) {
        if (samples == null || samples.length == 0) {
            return TonalFingerprint.empty(0);
        }
        int frameCount = (samples.length + FRAME_SAMPLES - 1) / FRAME_SAMPLES;
        byte[] chroma = new byte[frameCount * 12];
        byte[] chords = new byte[frameCount];
        byte[] pitches = new byte[frameCount];
        Arrays.fill(chords, UNKNOWN);
        Arrays.fill(pitches, UNKNOWN);

        FFT fft = new FFT(FFT_SIZE);
        float[] frame = new float[FFT_SIZE];
        double[] chromaAccumulator = new double[12];
        for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
            Arrays.fill(chromaAccumulator, 0d);
            int center = frameIndex * FRAME_SAMPLES;
            for (int offset = -FFT_SIZE / 4; offset < FFT_SIZE; offset += WINDOW_STEP) {
                transform(fft, frame, samples, center + offset);
                for (int bin = 4; bin < FFT_SIZE / 2; bin++) {
                    double frequency = bin * (double) AudioFingerprintEngine.SAMPLE_RATE / FFT_SIZE;
                    if (frequency < MIN_FREQUENCY || frequency > MAX_FREQUENCY) {
                        continue;
                    }
                    double midi = 69d + 12d * Math.log(frequency / 440d) / Math.log(2d);
                    int pitchClass = Math.floorMod((int) Math.round(midi), 12);
                    double magnitude = fft.modulus(frame, bin);
                    chromaAccumulator[pitchClass] += Math.sqrt(Math.max(0d, magnitude));
                }
            }

            double energy = 0d;
            for (double value : chromaAccumulator) {
                energy += value * value;
            }
            if (energy < MIN_CHROMA_ENERGY) {
                continue;
            }
            double norm = Math.sqrt(energy);
            int frameOffset = frameIndex * 12;
            int strongestPitch = 0;
            double strongestWeight = -1d;
            for (int pitchClass = 0; pitchClass < 12; pitchClass++) {
                double normalized = chromaAccumulator[pitchClass] / norm;
                chroma[frameOffset + pitchClass] = (byte) Math.min(255,
                        Math.round(normalized * 255d));
                if (chromaAccumulator[pitchClass] > strongestWeight) {
                    strongestWeight = chromaAccumulator[pitchClass];
                    strongestPitch = pitchClass;
                }
            }
            pitches[frameIndex] = (byte) strongestPitch;
            chords[frameIndex] = classifyChord(chromaAccumulator, norm);
        }
        return new TonalFingerprint(chroma, chords, pitches, frameCount, samples.length);
    }

    private void transform(FFT fft, float[] frame, short[] samples, int start) {
        Arrays.fill(frame, 0f);
        for (int i = 0; i < FFT_SIZE; i++) {
            int sampleIndex = start + i;
            if (sampleIndex < 0 || sampleIndex >= samples.length) {
                continue;
            }
            double window = 0.5d - 0.5d * Math.cos(2d * Math.PI * i / (FFT_SIZE - 1d));
            frame[i] = (float) (samples[sampleIndex] / 32768d * window);
        }
        fft.forwardTransform(frame);
    }

    private static byte classifyChord(double[] chroma, double norm) {
        int best = UNKNOWN;
        double bestScore = MIN_CHORD_SCORE;
        for (int chord = 0; chord < 24; chord++) {
            double dot = 0d;
            int base = chord * 12;
            for (int pitchClass = 0; pitchClass < 12; pitchClass++) {
                double weight = (chord & 1) == 0
                        ? MAJOR_TEMPLATE[Math.floorMod(pitchClass - (chord >> 1), 12)]
                        : MINOR_TEMPLATE[Math.floorMod(pitchClass - (chord >> 1), 12)];
                dot += chroma[pitchClass] / norm * weight;
            }
            double score = dot / Math.sqrt(3d);
            if (score > bestScore) {
                bestScore = score;
                best = (byte) chord;
            }
        }
        return (byte) best;
    }

    record TonalFingerprint(
            byte[] chroma,
            byte[] chords,
            byte[] pitches,
            int frameCount,
            long sampleCount) {
        TonalFingerprint {
            chroma = chroma == null ? new byte[0] : chroma;
            chords = chords == null ? new byte[0] : chords;
            pitches = pitches == null ? new byte[0] : pitches;
            if (frameCount < 0 || chroma.length < frameCount * 12
                    || chords.length < frameCount || pitches.length < frameCount) {
                throw new IllegalArgumentException("invalid tonal fingerprint");
            }
        }

        static TonalFingerprint empty(long sampleCount) {
            return new TonalFingerprint(new byte[0], new byte[0], new byte[0], 0, sampleCount);
        }

        double durationSeconds() {
            return sampleCount / (double) AudioFingerprintEngine.SAMPLE_RATE;
        }

        int chromaValue(int frame, int pitchClass) {
            return chroma[frame * 12 + pitchClass] & 0xff;
        }

        int chord(int frame) {
            return chords[frame] & 0xff;
        }

        int pitch(int frame) {
            return pitches[frame] & 0xff;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TonalFingerprint that)) {
                return false;
            }
            return frameCount == that.frameCount
                    && sampleCount == that.sampleCount
                    && Arrays.equals(chroma, that.chroma)
                    && Arrays.equals(chords, that.chords)
                    && Arrays.equals(pitches, that.pitches);
        }

        @Override
        public int hashCode() {
            int result = Long.hashCode(sampleCount);
            result = 31 * result + frameCount;
            result = 31 * result + Arrays.hashCode(chroma);
            result = 31 * result + Arrays.hashCode(chords);
            result = 31 * result + Arrays.hashCode(pitches);
            return result;
        }
    }

    record Match(
            int musicId,
            int alignedFeatures,
            double confidence,
            double offsetSeconds,
            double chromaSimilarity,
            int runnerUpFeatures) {
    }

    /** 和声与主音高轮廓的紧凑倒排索引。 */
    static final class Index {
        private final Map<Long, long[]> postings;
        private final int musicCount;

        private Index(Map<Long, long[]> postings, int musicCount) {
            this.postings = postings;
            this.musicCount = musicCount;
        }

        static Builder builder() {
            return new Builder(null);
        }

        static Builder builder(Index base) {
            return new Builder(base);
        }

        static Index restore(Map<Long, long[]> postings, int musicCount) {
            if (musicCount < 0) {
                throw new IllegalArgumentException("musicCount cannot be negative");
            }
            return new Index(Map.copyOf(postings), musicCount);
        }

        int musicCount() {
            return musicCount;
        }

        int uniqueHashCount() {
            return postings.size();
        }

        Map<Long, long[]> postingsView() {
            return postings;
        }

        static final class Builder {
            private final Map<Long, long[]> basePostings;
            private final Map<Long, LongList> mutable = new HashMap<>();
            private int indexedMusic;
            private long totalPostings;

            private Builder(Index base) {
                basePostings = base == null ? Map.of() : base.postings;
                indexedMusic = base == null ? 0 : base.musicCount;
                for (long[] values : basePostings.values()) {
                    totalPostings += values.length;
                }
            }

            void add(int musicId, TonalFingerprint fingerprint) {
                if (musicId <= 0 || fingerprint == null || fingerprint.frameCount() < PITCH_GRAM) {
                    return;
                }
                long totalBefore = totalPostings;
                addChordGrams(musicId, fingerprint);
                addPitchGrams(musicId, fingerprint);
                if (totalPostings > totalBefore) {
                    indexedMusic++;
                }
            }

            private void addChordGrams(int musicId, TonalFingerprint fingerprint) {
                int frames = fingerprint.frameCount();
                for (int frame = 0; frame <= frames - CHORD_GRAM; frame++) {
                    int first = fingerprint.chord(frame);
                    int second = fingerprint.chord(frame + 1);
                    int third = fingerprint.chord(frame + 2);
                    if (first == 0xff || second == 0xff || third == 0xff) {
                        continue;
                    }
                    append(chordHash(first, second, third), musicId, frame);
                }
            }

            private void addPitchGrams(int musicId, TonalFingerprint fingerprint) {
                int frames = fingerprint.frameCount();
                for (int frame = 0; frame <= frames - PITCH_GRAM; frame++) {
                    int first = fingerprint.pitch(frame);
                    int second = fingerprint.pitch(frame + 1);
                    int third = fingerprint.pitch(frame + 2);
                    if (first == 0xff || second == 0xff || third == 0xff) {
                        continue;
                    }
                    append(pitchHash(first, second, third), musicId, frame);
                }
            }

            private void append(long hash, int musicId, int frame) {
                if (totalPostings >= MAX_INDEX_TOTAL_POSTINGS) {
                    return;
                }
                long posting = ((long) musicId << 32) | (frame & 0xffff_ffffL);
                LongList values = mutable.computeIfAbsent(
                        hash, ignored -> new LongList(basePostings.get(hash)));
                values.add(posting);
                totalPostings++;
            }

            Index build() {
                Map<Long, long[]> additions = new HashMap<>();
                mutable.forEach((hash, values) -> additions.put(hash, values.toArray()));
                mutable.clear();
                if (basePostings.isEmpty()) {
                    return new Index(Map.copyOf(additions), indexedMusic);
                }
                // 与精确声纹索引相同：增量构建只在旧表上覆盖少量 hash，
                // 不复制整张基础表，避免额外占用数与百万计的条目。
                return new Index(new CompositePostings(basePostings, Map.copyOf(additions)), indexedMusic);
            }
        }

        Optional<Match> findBest(TonalFingerprint query, Map<Integer, TonalFingerprint> references) {
            if (query == null || query.frameCount() < PITCH_GRAM || postings.isEmpty()) {
                return Optional.empty();
            }
            List<QueryToken> tokens = queryTokens(query);
            if (tokens.isEmpty()) {
                return Optional.empty();
            }
            int chordTokens = 0;
            int pitchTokens = 0;
            for (QueryToken token : tokens) {
                if (token.chord()) {
                    chordTokens++;
                } else {
                    pitchTokens++;
                }
            }
            int minimumChordTokens = Math.max(MIN_QUERY_CHORD_TOKENS,
                    (int) Math.ceil(query.frameCount() * MIN_QUERY_CHORD_RATE));
            if (chordTokens < minimumChordTokens || pitchTokens == 0) {
                return Optional.empty();
            }

            Map<Long, Vote> votes = new HashMap<>();
            int comparisons = 0;
            for (QueryToken token : tokens) {
                long[] matches = postings.get(token.hash());
                if (matches == null || matches.length == 0) {
                    continue;
                }
                for (long posting : matches) {
                    int musicId = (int) (posting >>> 32);
                    int referenceFrame = (int) posting;
                    int delta = referenceFrame - token.frame();
                    if (delta < 0) {
                        continue;
                    }
                    long key = ((long) musicId << 32) | (delta & 0xffff_ffffL);
                    Vote vote = votes.computeIfAbsent(key, ignored -> new Vote());
                    if (token.chord()) {
                        vote.chord++;
                    } else {
                        vote.pitch++;
                    }
                    if (++comparisons >= MAX_COMPARISONS) {
                        break;
                    }
                }
                if (comparisons >= MAX_COMPARISONS) {
                    break;
                }
            }
            if (votes.isEmpty()) {
                return Optional.empty();
            }

            Map<Integer, Candidate> byMusic = new HashMap<>();
            for (Map.Entry<Long, Vote> entry : votes.entrySet()) {
                long key = entry.getKey();
                int musicId = (int) (key >>> 32);
                int delta = (int) key;
                Vote vote = entry.getValue();
                if (vote.chord < Math.max(2, (int) Math.ceil(chordTokens * MIN_CHORD_VOTE_RATE))
                        || vote.pitch < Math.max(3, (int) Math.ceil(pitchTokens * MIN_PITCH_VOTE_RATE))) {
                    continue;
                }
                double chordScore = Math.min(1d, vote.chord / (double) chordTokens);
                double pitchScore = Math.min(1d, vote.pitch / (double) pitchTokens);
                double featureScore = chordScore * 0.55d + pitchScore * 0.45d;
                Candidate candidate = byMusic.get(musicId);
                if (candidate == null || featureScore > candidate.featureScore) {
                    byMusic.put(musicId, new Candidate(
                            musicId, delta, featureScore, vote.chord, vote.pitch));
                }
            }
            List<Candidate> candidates = new ArrayList<>(byMusic.values());
            candidates.removeIf(candidate -> candidate.featureScore < 0.32d);
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            candidates.sort(Comparator.comparingDouble(Candidate::featureScore).reversed());
            if (candidates.size() > MAX_CANDIDATES) {
                candidates = candidates.subList(0, MAX_CANDIDATES);
            }

            List<VerifiedCandidate> verified = new ArrayList<>();
            int checked = 0;
            for (Candidate candidate : candidates) {
                TonalFingerprint reference = references.get(candidate.musicId);
                if (reference == null || reference.frameCount() < PITCH_GRAM) {
                    continue;
                }
                VerifiedCandidate result = verify(query, reference, candidate);
                if (result != null) {
                    verified.add(result);
                }
                if (++checked >= MAX_VERIFICATIONS) {
                    break;
                }
            }
            if (verified.isEmpty()) {
                return Optional.empty();
            }
            verified.sort(Comparator
                    .comparingDouble(VerifiedCandidate::score).reversed()
                    .thenComparing(Comparator.comparingInt(
                            VerifiedCandidate::alignedFeatures).reversed()));
            VerifiedCandidate best = verified.get(0);
            double minimumChroma = query.durationSeconds() <= SHORT_QUERY_SECONDS
                    ? MIN_SHORT_CHROMA_SIMILARITY
                    : MIN_CHROMA_SIMILARITY;
            if (best.score < 0.70d
                    || best.chromaSimilarity < minimumChroma
                    || best.chordAgreement < MIN_CHORD_AGREEMENT) {
                return Optional.empty();
            }
            VerifiedCandidate runnerUp = verified.size() > 1 ? verified.get(1) : null;
            if (runnerUp != null
                    && runnerUp.score >= 0.70d
                    && best.score < runnerUp.score + 0.05d
                    && best.score < runnerUp.score * 1.10d) {
                return Optional.empty();
            }
            return Optional.of(new Match(
                    best.musicId,
                    best.alignedFeatures,
                    Math.min(0.99d, best.score),
                    best.offsetSeconds,
                    best.chromaSimilarity,
                    runnerUp == null ? 0 : runnerUp.alignedFeatures));
        }

        private static List<QueryToken> queryTokens(TonalFingerprint query) {
            List<QueryToken> tokens = new ArrayList<>();
            int frames = query.frameCount();
            for (int frame = 0; frame <= frames - CHORD_GRAM; frame++) {
                int first = query.chord(frame);
                int second = query.chord(frame + 1);
                int third = query.chord(frame + 2);
                if (first != 0xff && second != 0xff && third != 0xff) {
                    tokens.add(new QueryToken(chordHash(first, second, third), frame, true));
                }
            }
            for (int frame = 0; frame <= frames - PITCH_GRAM; frame++) {
                int first = query.pitch(frame);
                int second = query.pitch(frame + 1);
                int third = query.pitch(frame + 2);
                if (first != 0xff && second != 0xff && third != 0xff) {
                    tokens.add(new QueryToken(pitchHash(first, second, third), frame, false));
                }
            }
            return tokens;
        }

        private static VerifiedCandidate verify(
                TonalFingerprint query,
                TonalFingerprint reference,
                Candidate candidate) {
            VerifiedCandidate best = null;
            for (double scale : TEMPO_SCALES) {
                for (int rotation = 0; rotation < 12; rotation++) {
                    double chromaSum = 0d;
                    int comparedFrames = 0;
                    int chordMatches = 0;
                    int chordComparable = 0;
                    for (int frame = 0; frame < query.frameCount(); frame++) {
                        int referenceFrame = candidate.delta + (int) Math.round(frame * scale);
                        if (referenceFrame < 0 || referenceFrame >= reference.frameCount()) {
                            continue;
                        }
                        double similarity = chromaSimilarity(query, reference, frame, referenceFrame, rotation);
                        if (similarity < -1d) {
                            continue;
                        }
                        chromaSum += similarity;
                        comparedFrames++;
                        int queryChord = query.chord(frame);
                        int referenceChord = reference.chord(referenceFrame);
                        if (queryChord != 0xff && referenceChord != 0xff) {
                            chordComparable++;
                            int queryRoot = (queryChord >> 1);
                            int referenceRoot = (referenceChord >> 1);
                            if ((queryChord & 1) == (referenceChord & 1)
                                    && Math.floorMod(queryRoot + rotation, 12) == referenceRoot) {
                                chordMatches++;
                            }
                        }
                    }
                    int minimumFrames = Math.max(4, Math.min(10, query.frameCount() / 2));
                    if (comparedFrames < minimumFrames) {
                        continue;
                    }
                    double chromaSimilarity = chromaSum / comparedFrames;
                    int minimumChordFrames = Math.max(4, Math.min(8, query.frameCount() / 3));
                    if (chordComparable < minimumChordFrames) {
                        continue;
                    }
                    double chordAgreement = chordMatches / (double) chordComparable;
                    double score = chromaSimilarity * 0.78d + chordAgreement * 0.22d;
                    int alignedFeatures = candidate.chord + candidate.pitch;
                    VerifiedCandidate result = new VerifiedCandidate(
                            candidate.musicId,
                            alignedFeatures,
                            score,
                            candidate.delta / (double) FRAME_RATE,
                            chromaSimilarity,
                            chordAgreement,
                            scale,
                            rotation);
                    if (best == null || result.score > best.score) {
                        best = result;
                    }
                }
            }
            return best;
        }

        private static double chromaSimilarity(
                TonalFingerprint query,
                TonalFingerprint reference,
                int queryFrame,
                int referenceFrame,
                int rotation) {
            double queryMean = 0d;
            double referenceMean = 0d;
            for (int pitchClass = 0; pitchClass < 12; pitchClass++) {
                queryMean += query.chromaValue(queryFrame, pitchClass);
                referenceMean += reference.chromaValue(referenceFrame, pitchClass);
            }
            queryMean /= 12d;
            referenceMean /= 12d;
            double dot = 0d;
            double queryEnergy = 0d;
            double referenceEnergy = 0d;
            for (int pitchClass = 0; pitchClass < 12; pitchClass++) {
                double queryValue = query.chromaValue(queryFrame, pitchClass) - queryMean;
                double referenceValue = reference.chromaValue(
                        referenceFrame, Math.floorMod(pitchClass + rotation, 12)) - referenceMean;
                dot += queryValue * referenceValue;
                queryEnergy += queryValue * queryValue;
                referenceEnergy += referenceValue * referenceValue;
            }
            if (queryEnergy < 1e-6d || referenceEnergy < 1e-6d) {
                return -2d;
            }
            return dot / Math.sqrt(queryEnergy * referenceEnergy);
        }

        private static long chordHash(int first, int second, int third) {
            int root = first >> 1;
            int firstInterval = Math.floorMod((second >> 1) - root, 12);
            int secondInterval = Math.floorMod((third >> 1) - root, 12);
            long qualities = (first & 1) | ((second & 1) << 1) | ((third & 1) << 2);
            return 1L << 56
                    | ((long) firstInterval << 3)
                    | ((long) secondInterval << 7)
                    | (qualities << 11);
        }

        private static long pitchHash(int first, int second, int third) {
            int firstInterval = Math.floorMod(second - first, 12);
            int secondInterval = Math.floorMod(third - first, 12);
            return 2L << 56
                    | firstInterval
                    | ((long) secondInterval << 4);
        }

        private record QueryToken(long hash, int frame, boolean chord) {
        }

        private static final class Vote {
            private int chord;
            private int pitch;
        }

        private record Candidate(
                int musicId,
                int delta,
                double featureScore,
                int chord,
                int pitch) {
        }

        private record VerifiedCandidate(
                int musicId,
                int alignedFeatures,
                double score,
                double offsetSeconds,
                double chromaSimilarity,
                double chordAgreement,
                double scale,
                int rotation) {
        }
    }
}
