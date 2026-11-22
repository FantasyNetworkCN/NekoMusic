package com.neko.music.util;

import com.neko.music.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上传音频的统一 FFmpeg 探测与解码校验入口。
 *
 * <p>上传格式不再依赖文件扩展名或少量魔数判断：FFmpeg 必须能识别出音频流并完成整段解码。
 * 对 MP4/MKV/WebM 等容器，只允许纯音频或带 attached_pic 封面的情况，带视频轨的文件会被拒绝。</p>
 */
public final class AudioMediaProbe {
    private static final Logger logger = LoggerFactory.getLogger(AudioMediaProbe.class);

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "mp3", "flac", "wav", "wave",
            "m4a", "m4b", "mp4", "aac", "alac",
            "ogg", "oga", "opus",
            "ape", "wma", "aiff", "aif",
            "ac3", "eac3", "ec3", "thd", "truehd", "mlp",
            "mka", "mkv", "webm", "dts", "wv", "caf");

    private static final Pattern DURATION =
            Pattern.compile("Duration:\\s*(\\d+):(\\d{2}):(\\d{2}(?:\\.\\d+)?)");
    private static final Pattern AUDIO_STREAM =
            Pattern.compile("Stream #\\d+:\\d+[^\\n]*?: Audio: ([^,\\n]+)[^\\n]*?, "
                    + "(\\d+) Hz, ([^,\\n]+), ([^,\\n]+)(?:, (\\d+) kb/s)?(?:,|\\s|$)");
    private static final Pattern CONTAINER_BITRATE =
            Pattern.compile("Duration:[^\\n]*bitrate: (\\d+) kb/s");
    private static final Pattern INPUT_FORMAT =
            Pattern.compile("Input #0, ([^\\n]+?) from ");
    private static final Pattern EXPLICIT_BITS = Pattern.compile("(\\d+) bit");
    private static final Pattern MANY_CHANNELS = Pattern.compile("(\\d+) channels");

    private AudioMediaProbe() {
    }

    public record MediaInfo(
            String format,
            String codec,
            String description,
            int durationSeconds,
            int sampleRateHz,
            int bitsPerSample,
            int channels,
            long bitrateBps
    ) {
    }

    public record Result(boolean valid, MediaInfo media, String errorMessage) {
        static Result success(MediaInfo media) {
            return new Result(true, media, null);
        }

        static Result fail(String errorMessage) {
            return new Result(false, null, errorMessage);
        }
    }

    /**
     * 探测并完整解码校验上传文件。返回的 {@code format} 是后端实际保存时使用的规范扩展名。
     */
    public static Result detectAndValidate(Path path, String fileExtension) {
        String extension = normalizeExtension(fileExtension);
        if (extension.isEmpty() || !SUPPORTED_EXTENSIONS.contains(extension)) {
            return Result.fail("upload.unsupported_audio");
        }
        if (path == null || !Files.isRegularFile(path)) {
            return Result.fail("上传文件不存在");
        }
        try {
            String output = inspect(path);
            if (containsVideoStream(output)) {
                return Result.fail("检测到视频轨，请上传纯音频文件");
            }
            Matcher stream = AUDIO_STREAM.matcher(output);
            if (!stream.find()) {
                return Result.fail("无法识别音频流");
            }

            String codec = stream.group(1).trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            int sampleRate = Integer.parseInt(stream.group(2));
            int channels = channelCount(stream.group(3));
            int bits = sampleBits(stream.group(4), codec);
            long bitrate = stream.group(5) == null ? 0 : Long.parseLong(stream.group(5)) * 1000L;
            int duration = durationSeconds(output);
            Matcher containerBitrate = CONTAINER_BITRATE.matcher(output);
            if (bitrate <= 0 && containerBitrate.find()) {
                bitrate = Long.parseLong(containerBitrate.group(1)) * 1000L;
            }
            Matcher input = INPUT_FORMAT.matcher(output);
            String inputFormat = input.find() ? input.group(1).trim() : extension;
            String format = canonicalFormat(extension, inputFormat, codec);
            if (format == null) {
                return Result.fail("upload.unsupported_audio");
            }
            String decodeError = validateDecodable(path);
            if (decodeError != null) {
                return Result.fail(decodeError);
            }
            return Result.success(new MediaInfo(
                    format,
                    codec,
                    inputFormat + " / " + codec,
                    Math.max(0, duration),
                    Math.max(0, sampleRate),
                    Math.max(0, bits),
                    Math.max(1, channels),
                    Math.max(0, bitrate)));
        } catch (Exception e) {
            logger.warn("音频探测失败 path={}: {}", path, e.toString());
            return Result.fail("读取音频失败: " + e.getMessage());
        }
    }

    public static int probeDurationSeconds(Path path) {
        try {
            return Math.max(0, durationSeconds(inspect(path)));
        } catch (Exception e) {
            logger.warn("读取音频时长失败 path={}: {}", path, e.toString());
            return 0;
        }
    }

    static String normalizeExtension(String fileExtension) {
        String ext = fileExtension == null ? "" : fileExtension.trim().toLowerCase(Locale.ROOT);
        while (ext.startsWith(".")) {
            ext = ext.substring(1);
        }
        return switch (ext) {
            case "wave" -> "wav";
            case "mp4", "m4b", "alac" -> "m4a";
            case "oga" -> "ogg";
            case "ec3" -> "eac3";
            case "truehd" -> "thd";
            case "aif" -> "aiff";
            case "mkv" -> "mka";
            default -> ext;
        };
    }

    private static String canonicalFormat(String extension, String inputFormat, String codec) {
        String format = inputFormat.toLowerCase(Locale.ROOT);
        if (format.contains("mov") || format.contains("mp4")) {
            return "m4a";
        }
        if (format.contains("matroska")) {
            return "webm".equals(extension) ? "webm" : "mka";
        }
        if (format.contains("ogg")) {
            return "opus".equals(codec) ? "opus" : "ogg";
        }
        if (format.contains("aiff")) {
            return "aiff";
        }
        if (format.contains("caf")) {
            return "caf";
        }
        if (format.contains("asf")) {
            return "wma";
        }
        if (format.contains("wavpack") || "wavpack".equals(codec)) {
            return "wv";
        }
        if (format.contains("dts")) {
            return "dts";
        }
        return switch (codec) {
            case "mp3" -> "mp3";
            case "flac" -> "flac";
            case "aac" -> "aac";
            case "ac3" -> "ac3";
            case "eac3" -> "eac3";
            case "truehd" -> "thd";
            case "mlp" -> "mlp";
            case "ape" -> "ape";
            case "dts" -> "dts";
            case "opus" -> "opus";
            case "vorbis" -> "ogg";
            default -> switch (extension) {
                case "wav" -> "wav";
                case "aiff" -> "aiff";
                case "m4a" -> "m4a";
                case "aac" -> "aac";
                case "ogg" -> "ogg";
                case "opus" -> "opus";
                case "ape" -> "ape";
                case "wma" -> "wma";
                case "ac3" -> "ac3";
                case "eac3" -> "eac3";
                case "thd" -> "thd";
                case "mlp" -> "mlp";
                case "mka" -> "mka";
                case "webm" -> "webm";
                case "dts" -> "dts";
                case "wv" -> "wv";
                case "caf" -> "caf";
                default -> null;
            };
        };
    }

    private static String inspect(Path path) throws IOException {
        String ffmpeg = resolveFfmpeg();
        Process process = new ProcessBuilder(
                ffmpeg, "-hide_banner", "-nostdin", "-i", path.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (InputStream input = process.getInputStream()) {
                input.transferTo(output);
            } catch (IOException ignored) {
                // 进程被超时终止时管道会关闭，退出码由下方统一处理。
            }
        });
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("FFmpeg 探测超时");
            }
            reader.join(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("FFmpeg 探测被中断", e);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String validateDecodable(Path path) {
        try {
            String ffmpeg = resolveFfmpeg();
            Process process = new ProcessBuilder(
                    ffmpeg, "-v", "error", "-nostdin", "-i", path.toAbsolutePath().toString(),
                    "-map", "0:a:0", "-vn", "-sn", "-dn", "-f", "null", "-")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            try {
                if (!process.waitFor(5, TimeUnit.MINUTES)) {
                    process.destroyForcibly();
                    return "音频解码校验超时";
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return "音频解码校验被中断";
            }
            return process.exitValue() == 0 ? null : "音频解码校验失败，文件可能损坏或编码不受支持";
        } catch (Exception e) {
            return "无法启动 FFmpeg 校验音频: " + e.getMessage();
        }
    }

    private static boolean containsVideoStream(String output) {
        for (String line : output.split("\\R")) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("stream #") && lower.contains(": video:") && !lower.contains("attached_pic")) {
                return true;
            }
        }
        return false;
    }

    private static int durationSeconds(String output) {
        Matcher matcher = DURATION.matcher(output);
        if (!matcher.find()) {
            return 0;
        }
        double seconds = Integer.parseInt(matcher.group(1)) * 3600
                + Integer.parseInt(matcher.group(2)) * 60
                + Double.parseDouble(matcher.group(3));
        return (int) Math.round(seconds);
    }

    private static int channelCount(String layout) {
        String value = layout.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("mono")) {
            return 1;
        }
        if (value.startsWith("stereo")) {
            return 2;
        }
        Matcher many = MANY_CHANNELS.matcher(value);
        if (many.find()) {
            return Integer.parseInt(many.group(1));
        }
        return switch (value) {
            case "2.1" -> 3;
            case "4.0", "quad" -> 4;
            case "5.1", "5.1(side)" -> 6;
            case "7.1", "7.1(side)", "5.1.2", "5.1.2(side)" -> 8;
            case "5.1.4", "7.1.2", "7.1.2(side)" -> 10;
            case "7.1.4" -> 12;
            default -> 2;
        };
    }

    private static int sampleBits(String sampleFormat, String codec) {
        Matcher explicit = EXPLICIT_BITS.matcher(sampleFormat);
        if (explicit.find()) {
            return Integer.parseInt(explicit.group(1));
        }
        String value = sampleFormat.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("u8") || value.startsWith("s8")) {
            return 8;
        }
        if (value.startsWith("s16") || value.startsWith("u16")) {
            return 16;
        }
        if (value.startsWith("s24") || value.startsWith("u24")) {
            return 24;
        }
        if (value.startsWith("s32") || value.startsWith("u32")) {
            return 32;
        }
        if (value.startsWith("s64") || value.startsWith("u64")) {
            return 64;
        }
        return codec.startsWith("pcm_") ? 16 : 0;
    }

    private static String resolveFfmpeg() throws IOException {
        if (Main.getConfigManager() == null) {
            return BundledFfmpegSupport.resolve("", false);
        }
        return BundledFfmpegSupport.resolve(
                Main.getConfigManager().getVideoRenderFfmpegPath(),
                Main.getConfigManager().isVideoRenderPreferBundledFfmpeg());
    }
}
