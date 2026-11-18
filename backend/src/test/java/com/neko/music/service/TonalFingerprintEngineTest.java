package com.neko.music.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TonalFingerprintEngineTest {
    private static final int[] ROOTS = {0, 5, 7, 3, 8, 0, 10, 5, 7, 0, 3, 8, 5, 7, 0, 3, 10, 5};
    private static final int[] QUALITIES = {0, 0, 1, 0, 1, 0, 1, 0, 0, 1, 0, 1, 1, 0, 0, 1, 0, 0};
    private static final int[] PITCHES = {0, 4, 7, 5, 9, 2, 7, 2, 11, 4, 7, 2, 5, 9, 0, 7, 2, 5};

    @Test
    void matchesSameHarmonicProgression() {
        TonalFingerprintEngine.TonalFingerprint reference = fingerprint(ROOTS, QUALITIES, PITCHES, 0);
        TonalFingerprintEngine.Index.Builder builder = TonalFingerprintEngine.Index.builder();
        builder.add(7, reference);
        TonalFingerprintEngine.Index index = builder.build();

        TonalFingerprintEngine.TonalFingerprint query = slice(reference, 5, 10);
        TonalFingerprintEngine.Match match = index.findBest(query, Map.of(7, reference)).orElseThrow();

        assertEquals(7, match.musicId());
        assertTrue(match.chromaSimilarity() >= 0.99d);
        assertEquals(2.5d, match.offsetSeconds(), 0.01d);
    }

    @Test
    void matchesTransposedPerformance() {
        TonalFingerprintEngine.TonalFingerprint reference = fingerprint(ROOTS, QUALITIES, PITCHES, 0);
        TonalFingerprintEngine.Index.Builder builder = TonalFingerprintEngine.Index.builder();
        builder.add(7, reference);
        TonalFingerprintEngine.Index index = builder.build();

        TonalFingerprintEngine.TonalFingerprint query =
                fingerprint(ROOTS, QUALITIES, PITCHES, 3);
        TonalFingerprintEngine.Match match = index.findBest(query, Map.of(7, reference)).orElseThrow();

        assertEquals(7, match.musicId());
        assertTrue(match.chromaSimilarity() >= 0.97d);
    }

    @Test
    void rejectsPitchOnlyEvidenceWithoutChordProgression() {
        TonalFingerprintEngine.TonalFingerprint reference = fingerprint(ROOTS, QUALITIES, PITCHES, 0);
        TonalFingerprintEngine.Index.Builder builder = TonalFingerprintEngine.Index.builder();
        builder.add(7, reference);
        TonalFingerprintEngine.Index index = builder.build();
        TonalFingerprintEngine.TonalFingerprint query = slice(reference, 5, 10);
        for (int frame = 0; frame < query.frameCount(); frame++) {
            query.chords()[frame] = (byte) 0xff;
        }

        assertTrue(index.findBest(query, Map.of(7, reference)).isEmpty());
    }

    @Test
    void rejectsHarmonicallyUnrelatedReference() {
        TonalFingerprintEngine.TonalFingerprint reference = fingerprint(ROOTS, QUALITIES, PITCHES, 0);
        TonalFingerprintEngine.Index.Builder builder = TonalFingerprintEngine.Index.builder();
        builder.add(7, reference);
        TonalFingerprintEngine.Index index = builder.build();
        TonalFingerprintEngine.TonalFingerprint query = fingerprint(
                new int[]{1, 2, 6, 9, 4, 11, 1, 6, 2, 9, 4, 1},
                new int[]{1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0},
                new int[]{1, 5, 8, 9, 1, 6, 3, 9, 5, 1, 6, 10},
                0);

        assertTrue(index.findBest(query, Map.of(7, reference)).isEmpty());
    }

    private static TonalFingerprintEngine.TonalFingerprint fingerprint(
            int[] roots, int[] qualities, int[] pitches, int rotation) {
        return fingerprint(roots, qualities, pitches, rotation, 0, 0);
    }

    private static TonalFingerprintEngine.TonalFingerprint fingerprint(
            int[] roots,
            int[] qualities,
            int[] pitches,
            int rotation,
            int frame,
            int deltaFrames) {
        int frames = Math.min(roots.length, Math.min(qualities.length, pitches.length));
        byte[] chroma = new byte[frames * 12];
        byte[] chords = new byte[frames];
        byte[] pitchClasses = new byte[frames];
        for (int i = 0; i < frames; i++) {
            int root = Math.floorMod(roots[i] + rotation, 12);
            int third = Math.floorMod(root + ((qualities[i] & 1) == 0 ? 4 : 3), 12);
            int fifth = Math.floorMod(root + 7, 12);
            for (int pc = 0; pc < 12; pc++) {
                int value = 18 + Math.floorMod(pc * 13 + i * 7, 48);
                if (pc == root) {
                    value = 248;
                } else if (pc == third) {
                    value = 205;
                } else if (pc == fifth) {
                    value = 178;
                } else if (pc == Math.floorMod(pitches[i] + rotation, 12)) {
                    value = 226;
                }
                chroma[i * 12 + pc] = (byte) value;
            }
            chords[i] = (byte) ((root << 1) | (qualities[i] & 1));
            pitchClasses[i] = (byte) Math.floorMod(pitches[i] + rotation, 12);
        }
        return new TonalFingerprintEngine.TonalFingerprint(
                chroma, chords, pitchClasses, frames,
                (long) (frames + deltaFrames) * TonalFingerprintEngine.FRAME_SAMPLES);
    }

    private static TonalFingerprintEngine.TonalFingerprint slice(
            TonalFingerprintEngine.TonalFingerprint source, int startFrame, int frameCount) {
        int frames = Math.min(frameCount, source.frameCount() - startFrame);
        byte[] chroma = new byte[frames * 12];
        byte[] chords = new byte[frames];
        byte[] pitches = new byte[frames];
        System.arraycopy(source.chroma(), startFrame * 12, chroma, 0, frames * 12);
        System.arraycopy(source.chords(), startFrame, chords, 0, frames);
        System.arraycopy(source.pitches(), startFrame, pitches, 0, frames);
        return new TonalFingerprintEngine.TonalFingerprint(
                chroma, chords, pitches, frames,
                (long) frames * TonalFingerprintEngine.FRAME_SAMPLES);
    }
}
