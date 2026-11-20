package com.neko.music.service;

import com.neko.music.benchmark.BenchmarkMeter;
import com.neko.music.config.ConfigManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** 固定合成倒排数据，分别测量索引可达内存和查询；不包含 FFmpeg、网络及数据库。 */
public final class RecognitionBenchmark {
    private static volatile Object result;

    public static void main(String[] arguments) throws Exception {
        int hashes = arguments.length == 0 ? 300_000 : Integer.parseInt(arguments[0]);
        Map<Long, long[]> stored = new HashMap<>();
        for (int i = 0; i < hashes; i++) {
            long hash = hash(i);
            stored.put(hash, new long[]{posting(1, i), posting(2, i + 10),
                    posting(3, i + 20), posting(4, i + 30)});
        }
        AudioFingerprintEngine.Index memoryIndex = AudioFingerprintEngine.Index.restore(stored, 80);
        long nativeBytes = 0;
        try {
            var nativeMemory = memoryIndex.getClass().getDeclaredMethod("nativeBytes");
            nativeMemory.setAccessible(true);
            nativeBytes = (long) nativeMemory.invoke(memoryIndex);
        } catch (NoSuchMethodException ignored) {
            // 兼容最初的 Java 基线。
        }
        long heapBytes = nativeBytes == 0 ? BenchmarkMeter.retainedBytes(memoryIndex) : -1;
        System.out.printf("index hashes=%d postings=%d heap_index_bytes=%d native_index_bytes=%d%n",
                memoryIndex.uniqueHashCount(), hashes * 4L, heapBytes, nativeBytes);
        stored = null;
        memoryIndex = null;
        System.gc();

        byte[] pcm = new byte[30 * AudioFingerprintEngine.SAMPLE_RATE * 2];
        new Random(7).nextBytes(pcm);
        // 反射同时兼容优化前的 private 方法，确保两版运行同一个探针。
        var pcmReader = AudioFingerprintEngine.class.getDeclaredMethod("readPcm", InputStream.class, long.class);
        pcmReader.setAccessible(true);
        BenchmarkMeter.measure("pcm_decode_30s", 1, 50, 100, () -> {
            try {
                result = pcmReader.invoke(null, new ByteArrayInputStream(pcm),
                        30L * AudioFingerprintEngine.SAMPLE_RATE);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
        });

        AudioFingerprintEngine.Index index = matchingIndex(80, 800);
        List<AudioFingerprintEngine.Landmark> ordered = landmarks(800);
        var query = fingerprint(ordered);
        var match = index.findBest(query, 6, 0.08).orElseThrow();
        if (match.musicId() != 1) {
            throw new AssertionError("合成查询匹配错误");
        }
        BenchmarkMeter.measure("recognition_ordered", 1, 100, 200,
                () -> result = index.findBest(query, 6, 0.08));

        List<AudioFingerprintEngine.Landmark> shuffled = new ArrayList<>(ordered);
        Collections.shuffle(shuffled, new Random(7));
        var shuffledQuery = fingerprint(shuffled);
        if (!index.findBest(shuffledQuery, 6, 0.08).equals(index.findBest(query, 6, 0.08))) {
            throw new AssertionError("无序输入改变匹配结果");
        }
        BenchmarkMeter.measure("recognition_shuffled", 1, 100, 200,
                () -> result = index.findBest(shuffledQuery, 6, 0.08));

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Random random = new Random(20261120);
        for (int attempt = 0; attempt < 2_000; attempt++) {
            List<AudioFingerprintEngine.Landmark> corpus = new ArrayList<>();
            int offset = random.nextInt(200);
            double slope = 0.9 + random.nextDouble() * 0.2;
            for (int i = 0; i < 800; i++) {
                if (random.nextInt(4) == 0) {
                    continue;
                }
                var point = new AudioFingerprintEngine.Landmark(
                        hash(i) + random.nextInt(5) - 2,
                        Math.max(0, (int) Math.round(i * 4 * slope) - offset),
                        random.nextInt(8));
                corpus.add(point);
                if (random.nextInt(5) == 0) {
                    corpus.add(new AudioFingerprintEngine.Landmark(point.hash(), point.timeFrame(), 9));
                }
                if (random.nextInt(8) == 0) {
                    corpus.add(new AudioFingerprintEngine.Landmark(hash(i + 20_000), i * 4));
                }
            }
            if ((attempt & 1) == 0) {
                Collections.shuffle(corpus, random);
            }
            String value = index.findBest(fingerprint(corpus), 6, 0.08).toString();
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        System.out.println("recognition_corpus_sha256=" + HexFormat.of().formatHex(digest.digest()));
        measureAudioPipeline();
    }

    /** 使用真实特征提取和同步识曲服务；跳过网络、鉴权与曲库构建。 */
    private static void measureAudioPipeline() throws Exception {
        short[] audio = new short[8 * AudioFingerprintEngine.SAMPLE_RATE];
        byte[] pcm = new byte[audio.length * 2];
        new Random(7).nextBytes(pcm);
        for (int i = 0; i < audio.length; i++) {
            audio[i] = (short) ((pcm[i * 2] & 0xff) | (pcm[i * 2 + 1] << 8));
        }
        TonalFingerprintEngine tonal = new TonalFingerprintEngine();
        BenchmarkMeter.measure("tonal_extract_8s", 1, 30, 60, () -> result = tonal.extract(audio));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int count : new int[]{0, 1, 7_999, 8_000, 16_001, audio.length}) {
            var fingerprint = tonal.extract(java.util.Arrays.copyOf(audio, count));
            digest.update(fingerprint.chroma());
            digest.update(fingerprint.chords());
            digest.update(fingerprint.pitches());
        }
        System.out.println("tonal_corpus_sha256=" + HexFormat.of().formatHex(digest.digest()));

        AudioFingerprintEngine engine = new AudioFingerprintEngine();
        var reference = engine.fingerprint(audio);
        var index = AudioFingerprintEngine.Index.build(Map.of(1, reference));
        var track = new MusicRecognitionService.Track(1, "合成录音", "性能测试", "", 8, "", "", "");
        var directory = Files.createTempDirectory("neko-recognition-benchmark-");
        MusicRecognitionService.FingerprintDecoder decoder = (file, duration, timeout) -> reference;
        try (MusicRecognitionService service = new MusicRecognitionService(
                new ConfigManager(), List::of, decoder, decoder,
                new MusicRecognitionService.FingerprintDiskCache(directory.resolve("tracks")),
                new MusicRecognitionService.FullIndexDiskCache(directory.resolve("library.nfi")),
                ids -> Map.of())) {
            var snapshot = MusicRecognitionService.class.getDeclaredField("currentIndex");
            snapshot.setAccessible(true);
            snapshot.set(service, new MusicRecognitionService.IndexSnapshot(
                    index, Map.of(1, track), List.of(), System.currentTimeMillis()));

            java.lang.reflect.Method prefixMethod;
            try {
                prefixMethod = MusicRecognitionService.class.getMethod("recognizePcm16k", byte[].class, int.class);
            } catch (NoSuchMethodException ignored) {
                prefixMethod = null;
            }
            var borrowed = prefixMethod;
            var expected = service.recognizePcm16k(pcm).orElseThrow();
            BenchmarkMeter.measure("stream_recognition_8s", 1, 20, 60, () -> {
                try {
                    // 分别模拟两版 WebSocket 调用：旧版复制有效前缀，新版同步借用缓冲区。
                    Object answer = borrowed == null
                            ? service.recognizePcm16k(pcm.clone())
                            : borrowed.invoke(service, pcm, pcm.length);
                    if (!java.util.Optional.of(expected).equals(answer)) {
                        throw new AssertionError("识曲服务结果不一致");
                    }
                    result = answer;
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            });
        } finally {
            Files.delete(directory);
        }
    }

    private static AudioFingerprintEngine.Index matchingIndex(int tracks, int points) {
        Map<Long, long[]> stored = new HashMap<>();
        for (int i = 0; i < points; i++) {
            List<Long> hits = new ArrayList<>();
            hits.add(posting(1, i * 4 + 1000));
            for (int track = 2; track <= tracks; track++) {
                if ((i + track) % (track + 1) == 0) {
                    hits.add(posting(track, i * 4 + track * 17));
                }
            }
            stored.put(hash(i), hits.stream().mapToLong(Long::longValue).toArray());
        }
        return AudioFingerprintEngine.Index.restore(stored, tracks);
    }

    private static List<AudioFingerprintEngine.Landmark> landmarks(int count) {
        List<AudioFingerprintEngine.Landmark> points = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            points.add(new AudioFingerprintEngine.Landmark(hash(i), i * 4));
        }
        return points;
    }

    private static AudioFingerprintEngine.Fingerprint fingerprint(List<AudioFingerprintEngine.Landmark> points) {
        return new AudioFingerprintEngine.Fingerprint(points, 8L * AudioFingerprintEngine.SAMPLE_RATE);
    }

    private static long hash(int value) {
        return (value * 0x9e3779b9L) & ((1L << 34) - 1);
    }

    private static long posting(int track, int time) {
        return ((long) track << 32) | (time & 0xffff_ffffL);
    }
}
