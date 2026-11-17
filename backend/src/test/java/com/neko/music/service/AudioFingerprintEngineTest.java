package com.neko.music.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioFingerprintEngineTest {
    @Test
    void matchesAStableSyntheticTrackAndRejectsUnrelatedTrack() {
        AudioFingerprintEngine engine = new AudioFingerprintEngine();
        short[] track = waveform(18, 0);
        short[] other = waveform(18, 1);
        AudioFingerprintEngine.Fingerprint trackFingerprint = engine.fingerprint(track);
        AudioFingerprintEngine.Fingerprint otherFingerprint = engine.fingerprint(other);
        AudioFingerprintEngine.Index index = AudioFingerprintEngine.Index.build(
                Map.of(11, trackFingerprint, 22, otherFingerprint));

        AudioFingerprintEngine.Fingerprint query = engine.fingerprint(slice(track, 4, 8));
        AudioFingerprintEngine.Match match = index.findBest(query, 3, 0.05).orElseThrow();
        assertEquals(11, match.musicId());
        assertTrue(match.confidence() >= 0.05);

        assertTrue(index.findBest(engine.fingerprint(new short[8 * AudioFingerprintEngine.SAMPLE_RATE]), 3, 0.05).isEmpty());
    }

    @Test
    void acceptsShortQueryWithScaledMinimumMatchCount() {
        AudioFingerprintEngine engine = new AudioFingerprintEngine();
        short[] track = waveform(18, 0);
        AudioFingerprintEngine.Fingerprint trackFingerprint = engine.fingerprint(track);
        AudioFingerprintEngine.Index index = AudioFingerprintEngine.Index.build(Map.of(11, trackFingerprint));

        AudioFingerprintEngine.Fingerprint query = engine.fingerprint(slice(track, 7, 3));
        assertTrue(index.findBest(query, 6, 0.05).isPresent());
    }

    @Test
    void keepsEnoughLandmarksToRecognizeShortClipFromLongTrack() {
        AudioFingerprintEngine engine = new AudioFingerprintEngine();
        short[] track = richWaveform(90, 7);
        AudioFingerprintEngine.Fingerprint full = engine.fingerprint(track);
        AudioFingerprintEngine.Fingerprint bounded = MusicRecognitionService.limitLandmarks(full);

        assertTrue(bounded.landmarks().size() > 0);
        assertTrue(bounded.landmarks().size()
                <= MusicRecognitionService.MAX_LANDMARKS_PER_TRACK);
        AudioFingerprintEngine.Index index =
                AudioFingerprintEngine.Index.build(Map.of(11, bounded));
        AudioFingerprintEngine.Fingerprint query = engine.fingerprint(slice(track, 30, 8));

        AudioFingerprintEngine.Match match = index.findBest(query, 3, 0.03).orElseThrow();
        assertEquals(11, match.musicId());
        assertTrue(match.alignedLandmarks() >= 3);
    }

    @Test
    void allocatesMoreLandmarksToVeryLongTracks() {
        List<AudioFingerprintEngine.Landmark> landmarks = new java.util.ArrayList<>();
        for (int i = 0; i < 50_000; i++) {
            landmarks.add(new AudioFingerprintEngine.Landmark(i, i));
        }
        AudioFingerprintEngine.Fingerprint longTrack = new AudioFingerprintEngine.Fingerprint(
                landmarks, 700L * AudioFingerprintEngine.SAMPLE_RATE);

        AudioFingerprintEngine.Fingerprint bounded =
                MusicRecognitionService.limitLandmarks(longTrack);

        assertEquals(MusicRecognitionService.MAX_LANDMARKS_PER_LONG_TRACK, bounded.landmarks().size());
    }

    @Test
    void confidenceIgnoresQueryHashesMissingFromASparseIndex() {
        AudioFingerprintEngine engine = new AudioFingerprintEngine();
        short[] track = richWaveform(90, 7);
        AudioFingerprintEngine.Fingerprint full = engine.fingerprint(track);
        AudioFingerprintEngine.Fingerprint sparse = sparseLandmarks(full, 1_500);
        AudioFingerprintEngine.Index index =
                AudioFingerprintEngine.Index.build(Map.of(11, sparse));
        AudioFingerprintEngine.Fingerprint query = engine.fingerprint(slice(track, 30, 8));

        AudioFingerprintEngine.Match match = index.findBest(query, 3, 0.03).orElseThrow();
        assertEquals(11, match.musicId());
        assertTrue(match.confidence() >= 0.03);
    }

    @Test
    void rejectsUnrelatedShortClipAgainstLongTrackBudget() {
        AudioFingerprintEngine engine = new AudioFingerprintEngine();
        short[] track = waveform(180, 0);
        AudioFingerprintEngine.Index index = AudioFingerprintEngine.Index.build(Map.of(
                11, MusicRecognitionService.limitLandmarks(engine.fingerprint(track))));
        AudioFingerprintEngine.Fingerprint query = engine.fingerprint(noise(8, 42));

        assertTrue(index.findBest(query, 6, 0.08).isEmpty());
    }

    @Test
    void recognizesTheResampled16kWebSocketStreamUnderNoise() {
        AudioFingerprintEngine engine = new AudioFingerprintEngine();
        short[] track = richWaveform(90, 7);
        AudioFingerprintEngine.Index index = AudioFingerprintEngine.Index.build(Map.of(
                11, MusicRecognitionService.limitLandmarks(engine.fingerprint(track))));
        short[] queryAt16k = slice(track, 30, 8);
        AudioFingerprintEngine.Fingerprint query =
                engine.fingerprint(MusicRecognitionService.resample16kToEngineRate(queryAt16k));

        AudioFingerprintEngine.Match match = index.findBest(query, 6, 0.08).orElseThrow();
        assertEquals(11, match.musicId());
    }

    @Test
    void indexDeduplicatesLandmarksPerTrack() {
        AudioFingerprintEngine engine = new AudioFingerprintEngine();
        AudioFingerprintEngine.Fingerprint fp = engine.fingerprint(waveform(8, 0));
        AudioFingerprintEngine.Index index = AudioFingerprintEngine.Index.build(new HashMap<>(Map.of(1, fp, 2, fp)));
        assertEquals(2, index.musicCount());
        assertTrue(index.uniqueHashCount() > 0);
    }

    @Test
    void incrementalBuilderRetainsBaseTracksAndAddsNewTrack() {
        AudioFingerprintEngine engine = new AudioFingerprintEngine();
        AudioFingerprintEngine.Fingerprint first = engine.fingerprint(waveform(8, 0));
        AudioFingerprintEngine.Fingerprint second = engine.fingerprint(waveform(8, 1));
        AudioFingerprintEngine.Index base = AudioFingerprintEngine.Index.build(Map.of(1, first));

        AudioFingerprintEngine.Index.Builder builder = AudioFingerprintEngine.Index.builder(base);
        builder.add(2, second);
        AudioFingerprintEngine.Index incremental = builder.build();

        assertEquals(2, incremental.musicCount());
        assertTrue(incremental.uniqueHashCount() >= base.uniqueHashCount());
    }

    @Test
    void producesStableFingerprintsWhenSharedAcrossWorkerThreads() throws Exception {
        AudioFingerprintEngine engine = new AudioFingerprintEngine();
        short[] track = waveform(6, 0);
        AudioFingerprintEngine.Fingerprint expected = engine.fingerprint(track);
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Callable<AudioFingerprintEngine.Fingerprint>> tasks = List.of(
                    () -> engine.fingerprint(track),
                    () -> engine.fingerprint(track),
                    () -> engine.fingerprint(track),
                    () -> engine.fingerprint(track),
                    () -> engine.fingerprint(track),
                    () -> engine.fingerprint(track),
                    () -> engine.fingerprint(track),
                    () -> engine.fingerprint(track));
            for (Future<AudioFingerprintEngine.Fingerprint> result : workers.invokeAll(tasks)) {
                assertEquals(expected, result.get());
            }
        } finally {
            workers.shutdownNow();
        }
    }

    private static short[] waveform(int seconds, int variant) {
        return waveformAtRate(AudioFingerprintEngine.SAMPLE_RATE, seconds, variant, 0d);
    }

    private static short[] waveformAtRate(int sampleRate, int seconds, int variant, double noiseLevel) {
        int count = seconds * sampleRate;
        short[] result = new short[count];
        double[] frequencies = variant == 0
                ? new double[]{220, 330, 440, 554}
                : variant == 1
                ? new double[]{247, 371, 494, 622}
                : new double[]{173, 281, 419, 691};
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < count; i++) {
            double t = i / (double) sampleRate;
            double sweep = Math.sin(2 * Math.PI * (40 + 7 * t) * t);
            double signal = 0;
            for (int j = 0; j < frequencies.length; j++) {
                signal += Math.sin(2 * Math.PI * (frequencies[j] + (j + 1) * 2 * t) * t + sweep * 0.4) / frequencies.length;
            }
            signal += noiseLevel * (random.nextDouble() * 2d - 1d);
            signal = Math.max(-1d, Math.min(1d, signal));
            result[i] = (short) Math.round(signal * 20_000);
        }
        return result;
    }

    private static short[] slice(short[] source, int startSeconds, int durationSeconds) {
        int start = startSeconds * AudioFingerprintEngine.SAMPLE_RATE;
        int length = durationSeconds * AudioFingerprintEngine.SAMPLE_RATE;
        short[] result = new short[length];
        System.arraycopy(source, start, result, 0, length);
        return result;
    }

    private static short[] noise(int seconds, long seed) {
        short[] result = new short[seconds * AudioFingerprintEngine.SAMPLE_RATE];
        java.util.Random random = new java.util.Random(seed);
        for (int i = 0; i < result.length; i++) {
            result[i] = (short) random.nextInt(16_384);
        }
        return result;
    }

    private static short[] richWaveform(int seconds, int seed) {
        int count = seconds * AudioFingerprintEngine.SAMPLE_RATE;
        short[] result = new short[count];
        Random random = new Random(seed);
        double[] frequencies = new double[6];
        double[] phases = new double[6];
        for (int i = 0; i < frequencies.length; i++) {
            frequencies[i] = 120 + random.nextDouble() * 1_800;
            phases[i] = random.nextDouble() * Math.PI * 2;
        }
        for (int i = 0; i < count; i++) {
            double time = i / (double) AudioFingerprintEngine.SAMPLE_RATE;
            if (i % 8_000 == 0) {
                for (int j = 0; j < frequencies.length; j++) {
                    frequencies[j] = 100 + random.nextDouble() * 2_200;
                    phases[j] = random.nextDouble() * Math.PI * 2;
                }
            }
            double signal = 0;
            for (int j = 0; j < frequencies.length; j++) {
                signal += Math.sin(2 * Math.PI * frequencies[j] * time + phases[j])
                        / frequencies.length;
            }
            signal += 0.015 * (random.nextDouble() * 2 - 1);
            result[i] = (short) Math.round(
                    Math.max(-1, Math.min(1, signal)) * 22_000);
        }
        return result;
    }

    private static AudioFingerprintEngine.Fingerprint sparseLandmarks(
            AudioFingerprintEngine.Fingerprint fingerprint,
            int maximum) {
        List<AudioFingerprintEngine.Landmark> landmarks = fingerprint.landmarks();
        if (landmarks.size() <= maximum) {
            return fingerprint;
        }
        List<AudioFingerprintEngine.Landmark> sampled = new java.util.ArrayList<>(maximum);
        double step = (landmarks.size() - 1d) / (maximum - 1d);
        for (int i = 0; i < maximum; i++) {
            sampled.add(landmarks.get((int) Math.round(i * step)));
        }
        return new AudioFingerprintEngine.Fingerprint(sampled, fingerprint.sampleCount());
    }
}
