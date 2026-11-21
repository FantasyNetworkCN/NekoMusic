package com.neko.music.service;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

/** 同一探针分别运行改动前后 class，比较不同录音边界和长曲目分块的每个特征。 */
public final class RecognitionFeatureEquivalenceBenchmark {
    public static void main(String[] arguments) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        PanakoSpectrumExtractor extractor = new PanakoSpectrumExtractor();
        ByteBuffer encoded = ByteBuffer.allocate(16);
        for (int length : new int[]{0, 1, 127, 128, 2047, 2048, 7999, 8000, 16001, 128000, 121 * 16000}) {
            short[] samples = new short[length];
            Random random = new Random(7);
            for (int i = 0; i < length; i++) {
                samples[i] = (short) random.nextInt(65536);
            }
            for (boolean query : new boolean[]{false, true}) {
                // 长曲目验证索引分块，实时查询则验证短录音和单窗口。
                if (query && length > 128000) {
                    continue;
                }
                var features = extractor.extract(samples, query);
                for (var feature : features) {
                    encoded.clear();
                    encoded.putLong(feature.hash()).putInt(feature.timeFrame()).putInt(feature.frequencyBin());
                    digest.update(encoded.array());
                }
                System.out.printf("samples=%d query=%s features=%d%n", length, query, features.size());
            }
        }
        System.out.println("features_sha256=" + HexFormat.of().formatHex(digest.digest()));
    }
}
