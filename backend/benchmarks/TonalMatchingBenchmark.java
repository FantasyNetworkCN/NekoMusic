package com.neko.music.service;

import com.neko.music.benchmark.BenchmarkMeter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** 单独测量经典匹配未命中后才执行的和声召回 / 验证，使用固定合成和声曲库。 */
public final class TonalMatchingBenchmark {
    private static volatile Object result;

    public static void main(String[] arguments) {
        for (int tracks : new int[]{100, 1000}) {
            Random random = new Random(20261121);
            Map<Integer, TonalFingerprintEngine.TonalFingerprint> references = new HashMap<>();
            var builder = TonalFingerprintEngine.Index.builder();
            for (int id = 1; id <= tracks; id++) {
                var fingerprint = track(random, 120);
                references.put(id, fingerprint);
                builder.add(id, fingerprint);
            }
            var index = builder.build();
            var reference = references.get(7);
            int first = 35;
            int frames = 16;
            var query = new TonalFingerprintEngine.TonalFingerprint(
                    Arrays.copyOfRange(reference.chroma(), first * 12, (first + frames) * 12),
                    Arrays.copyOfRange(reference.chords(), first, first + frames),
                    Arrays.copyOfRange(reference.pitches(), first, first + frames),
                    frames, (long) frames * TonalFingerprintEngine.FRAME_SAMPLES);
            var expected = index.findBest(query, references).orElseThrow();
            if (expected.musicId() != 7) {
                throw new AssertionError("和声基准未匹配正确歌曲");
            }
            System.out.println("tonal_tracks=" + tracks + " match=" + expected);
            BenchmarkMeter.measure("tonal_match_" + tracks + "_tracks", 1, 10, 30, () -> {
                var match = index.findBest(query, references);
                if (!match.equals(java.util.Optional.of(expected))) {
                    throw new AssertionError("和声匹配结果不稳定");
                }
                result = match;
            });
        }
    }

    private static TonalFingerprintEngine.TonalFingerprint track(Random random, int frames) {
        byte[] chroma = new byte[frames * 12];
        byte[] chords = new byte[frames];
        byte[] pitches = new byte[frames];
        for (int i = 0; i < frames; i++) {
            int root = random.nextInt(12);
            int quality = random.nextInt(2);
            int third = (root + (quality == 0 ? 4 : 3)) % 12;
            int fifth = (root + 7) % 12;
            for (int pc = 0; pc < 12; pc++) {
                chroma[i * 12 + pc] = (byte) (pc == root ? 248
                        : pc == third ? 205 : pc == fifth ? 178 : 18 + random.nextInt(48));
            }
            chords[i] = (byte) ((root << 1) | quality);
            pitches[i] = (byte) root;
        }
        return new TonalFingerprintEngine.TonalFingerprint(
                chroma, chords, pitches, frames, (long) frames * TonalFingerprintEngine.FRAME_SAMPLES);
    }
}
