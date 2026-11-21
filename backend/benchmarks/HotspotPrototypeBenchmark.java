package com.neko.music.service;

import com.neko.music.benchmark.BenchmarkMeter;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * 仅在离线探针中验证两种热点替代算法；不接入生产路径。
 * 先逐项比较结果，再测量 CPU 与分配，避免用不同语义换取加速。
 */
public final class HotspotPrototypeBenchmark {
    private static volatile Object result;
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public static void main(String[] arguments) throws Exception {
        measureFrequencyFilter();
        measureTextNormalization();
    }

    private static void measureFrequencyFilter() throws Exception {
        Method baseline;
        try {
            baseline = PanakoSpectrumExtractor.class.getDeclaredMethod(
                    "maxFilterClamped", float[].class, float[].class, int.class);
        } catch (NoSuchMethodException optimized) {
            baseline = PanakoSpectrumExtractor.class.getDeclaredMethod("maxFilterClamped",
                    float[].class, float[].class, int.class, float[].class, int[].class);
        }
        baseline.setAccessible(true);
        float[][] frames = new float[1000][512];
        Random random = new Random(7);
        for (float[] frame : frames) {
            for (int i = 0; i < frame.length; i++) {
                frame[i] = random.nextInt(7) == 0 ? 0f : random.nextFloat();
            }
        }
        float[] expected = new float[512];
        float[] actual = new float[512];
        float[] padded = new float[512 + 102];
        int[] deque = new int[padded.length];
        Method filter = baseline;
        Object[] call = baseline.getParameterCount() == 3
                ? new Object[]{null, actual, 103} : new Object[]{null, actual, 103, padded, deque};
        for (float[] frame : frames) {
            call[0] = frame;
            call[1] = expected;
            filter.invoke(null, call);
            primitiveFilter(frame, actual, 103, padded, deque);
            if (!Arrays.equals(expected, actual)) {
                throw new AssertionError("频域滤波结果变化");
            }
        }
        System.out.println("frequency_filter_equivalent_frames=" + frames.length);
        BenchmarkMeter.measure("frequency_filter_current_per_frame", frames.length, 10, 30, () -> {
            try {
                for (float[] frame : frames) {
                    call[0] = frame;
                    call[1] = actual;
                    filter.invoke(null, call);
                }
                result = actual;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        BenchmarkMeter.measure("frequency_filter_primitive_prototype_per_frame", frames.length, 10, 30, () -> {
            for (float[] frame : frames) {
                primitiveFilter(frame, actual, 103, padded, deque);
            }
            result = actual;
        });
    }

    /** 复用原始类型工作区，完全保留夹边与单调队列的比较顺序。 */
    private static void primitiveFilter(float[] input, float[] output, int window,
                                        float[] padded, int[] deque) {
        int half = window / 2;
        Arrays.fill(padded, 0, half, input[0]);
        System.arraycopy(input, 0, padded, half, input.length);
        Arrays.fill(padded, half + input.length, padded.length, input[input.length - 1]);
        int head = 0;
        int tail = 0;
        for (int i = 0; i < padded.length; i++) {
            while (head < tail && padded[deque[tail - 1]] <= padded[i]) {
                tail--;
            }
            deque[tail++] = i;
            if (deque[head] <= i - window) {
                head++;
            }
            if (i >= window - 1) {
                output[i - window + 1] = padded[deque[head]];
            }
        }
    }

    private static void measureTextNormalization() {
        Random random = new Random(20261121);
        for (int i = 0; i < 10000; i++) {
            StringBuilder value = new StringBuilder("İ ΑΣ Ⅷ ² 𠀀 𐐀 \uD800 / ");
            for (int j = 0; j < 16; j++) {
                value.appendCodePoint(random.nextInt(Character.MAX_CODE_POINT + 1));
            }
            String input = value.toString();
            if (!normalizeCurrent(input).equals(normalizePrototype(input))) {
                throw new AssertionError("Unicode 文本规范化结果变化，样本 " + i);
            }
        }
        System.out.println("normalization_equivalent_unicode_samples=10000");
        String[] texts = new String[600];
        for (int i = 0; i < texts.length; i++) {
            texts[i] = ("歌单 " + i + " 周杰伦 晴天 ARTIST " + (i % 9)
                    + " 轻音乐 ロック jazz / live ").repeat(30);
        }
        BenchmarkMeter.measure("normalization_current_600", 1, 10, 30, () -> {
            for (String text : texts) {
                result = normalizeCurrent(text);
            }
        });
        BenchmarkMeter.measure("normalization_scan_prototype_600", 1, 10, 30, () -> {
            for (String text : texts) {
                result = normalizePrototype(text);
            }
        });
    }

    private static String normalizeCurrent(String text) {
        return WHITESPACE.matcher(NON_WORD.matcher(text.toLowerCase(Locale.ROOT))
                .replaceAll(" ")).replaceAll(" ").trim();
    }

    /** 保留 Locale.ROOT 小写，以及 Unicode 字母、三类数字与 ASCII 分隔空格的现有语义。 */
    private static String normalizePrototype(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(lower.length());
        boolean separated = false;
        for (int i = 0; i < lower.length(); ) {
            int cp = lower.codePointAt(i);
            int type = Character.getType(cp);
            boolean word = Character.isLetter(cp)
                    || type == Character.DECIMAL_DIGIT_NUMBER
                    || type == Character.LETTER_NUMBER
                    || type == Character.OTHER_NUMBER;
            if (word) {
                if (separated && !normalized.isEmpty()) {
                    normalized.append(' ');
                }
                normalized.appendCodePoint(cp);
                separated = false;
            } else {
                separated = true;
            }
            i += Character.charCount(cp);
        }
        return normalized.toString();
    }
}
