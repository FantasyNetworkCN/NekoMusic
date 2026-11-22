package com.neko.music.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;

/**
 * 在运行目录下按 {@code Music/{id}.*} 定位音频，不依赖数据库路径列。
 * 封面优先使用 {@code Music/.covers/{id}.*} 独立文件，同时兼容旧的内嵌封面。
 */
public final class MusicAssetLocator {
    private static final Logger log = LoggerFactory.getLogger(MusicAssetLocator.class);

    public static final String AUDIO_REL_DIR = "Music";
    private static final String COVER_REL_DIR = ".covers";

    private static final List<String> AUDIO_EXT_PRIORITY = List.of(
            "flac", "wav", "eac3", "ec3", "ac3", "thd", "truehd", "mlp",
            "m4a", "mp4", "aac", "ogg", "opus", "mp3", "ape", "wma", "aiff", "aif",
            "mka", "webm", "dts", "wv", "caf");
    private static final List<String> COVER_EXT_PRIORITY = List.of(
            "jpg", "jpeg", "png", "gif", "bmp", "webp");

    private MusicAssetLocator() {
    }

    /**
     * 运行目录（{@code user.dir}）在进程内不变。写成静态常量，省掉每次资产请求都做一遍
     * {@code System.getProperty} + {@code Paths.get}：音频请求里 {@link #audioDir()}
     * 一类方法会被调用好几次。
     */
    private static final Path BASE_DIR = Paths.get(System.getProperty("user.dir"));

    public static Path baseDir() {
        return BASE_DIR;
    }

    public static Path audioDir() {
        return baseDir().resolve(AUDIO_REL_DIR);
    }

    public static Path coverDir() {
        return audioDir().resolve(COVER_REL_DIR);
    }

    public static Path derivedDir(int musicId) {
        return audioDir().resolve("derived").resolve(Integer.toString(musicId));
    }

    public static Optional<Path> findAudioFile(int musicId) {
        if (musicId <= 0) {
            return Optional.empty();
        }
        try {
            return findIdNamedFile(audioDir(), musicId, AUDIO_EXT_PRIORITY);
        } catch (IOException e) {
            log.warn("定位音乐文件失败: {}", e.toString());
            return Optional.empty();
        }
    }

    /** 上传时保存的独立封面；所有音频容器都可使用，避免依赖容器标签能力。 */
    public static Optional<Path> findCoverSidecar(int musicId) {
        if (musicId <= 0) {
            return Optional.empty();
        }
        try {
            return findIdNamedFile(coverDir(), musicId, COVER_EXT_PRIORITY);
        } catch (IOException e) {
            log.warn("定位封面文件失败 musicId={}: {}", musicId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 原子替换曲目独立封面并清理旧扩展名。封面属于展示增强，调用方应自行决定失败是否阻断上传。
     */
    public static Path replaceCoverSidecar(int musicId, Path source) throws IOException {
        if (musicId <= 0 || source == null || !Files.isRegularFile(source)) {
            throw new IOException("封面文件不存在");
        }
        String extension = extension(source);
        if (!COVER_EXT_PRIORITY.contains(extension)) {
            extension = "jpg";
        }
        Path dir = coverDir();
        Files.createDirectories(dir);
        Path target = dir.resolve(musicId + "." + extension).toAbsolutePath().normalize();
        Path temp = Files.createTempFile(dir, musicId + "-cover-", ".tmp");
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        deleteCoverVariantsExcept(musicId, target);
        return target;
    }

    public static void deleteCoverSidecar(int musicId) {
        Path dir = coverDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        String prefix = musicId + ".";
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> isSingleSegmentExtension(path.getFileName().toString(), prefix))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.warn("删除封面文件失败 musicId={} path={}: {}", musicId, path, e.toString());
                        }
                    });
        } catch (IOException e) {
            log.warn("列出封面目录失败 musicId={}: {}", musicId, e.toString());
        }
    }

    /**
     * 批量定位音频文件，只遍历一次音乐目录。声纹全量构建使用此方法，避免按歌曲重复扫描目录。
     */
    public static Map<Integer, Path> findAudioFiles(Collection<Integer> musicIds) throws IOException {
        if (musicIds == null || musicIds.isEmpty() || !Files.isDirectory(audioDir())) {
            return Map.of();
        }
        Set<Integer> wanted = new HashSet<>(musicIds);
        Map<Integer, List<Path>> candidates = new HashMap<>();
        try (Stream<Path> stream = Files.list(audioDir())) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                String name = path.getFileName().toString();
                int dot = name.indexOf('.');
                if (dot <= 0 || name.indexOf('.', dot + 1) >= 0) {
                    return;
                }
                int musicId;
                try {
                    musicId = Integer.parseInt(name.substring(0, dot));
                } catch (NumberFormatException ignored) {
                    return;
                }
                String extension = name.substring(dot + 1);
                if (wanted.contains(musicId) && AUDIO_EXT_PRIORITY.contains(extension)) {
                    candidates.computeIfAbsent(musicId, ignored -> new ArrayList<>()).add(path);
                }
            });
        }
        Map<Integer, Path> selected = new HashMap<>();
        candidates.forEach((musicId, files) -> pickOne(files, AUDIO_EXT_PRIORITY)
                .ifPresent(path -> selected.put(musicId, path)));
        return Map.copyOf(selected);
    }

    public static String fileApiUrl(int musicId) {
        return "/api/music/file/" + musicId;
    }

    public static String coverApiUrl(int musicId) {
        return "/api/music/cover/" + musicId;
    }

    /** 公开静态封面地址：与 {@code /media/music/*} 同源同缓存语义，供 SEO 页与链接预览直接引用。 */
    public static String mediaCoverUrl(int musicId) {
        return "/media/cover/" + musicId;
    }

    /** 公开静态音频地址：og:audio 与 JSON-LD 使用，避免引用只返回 JSON 的解析接口。 */
    public static String mediaMusicUrl(int musicId) {
        return "/media/music/" + musicId;
    }

    /** 尽力删除 {@code Music/{musicId}.*}；单文件失败只打 WARN，不抛异常。 */
    public static void deleteAudioVariants(int musicId) {
        deleteIdNamedFiles(audioDir(), musicId, "audio");
        deleteDerivedVariants(musicId);
        deleteCoverSidecar(musicId);
    }

    /**
     * 尽力删除 {@code Music/{musicId}.*} 中除 {@code keep} 之外的变体。
     *
     * <p>替换音频时先写入新文件、再清理旧扩展名的残留，避免新文件写失败时旧文件已被删除。
     */
    public static void deleteAudioVariantsExcept(int musicId, Path keep) {
        deleteIdNamedFilesExcept(audioDir(), musicId, keep, "audio");
        deleteDerivedVariants(musicId);
        deleteCoverSidecar(musicId);
    }

    public static void deleteDerivedVariants(int musicId) {
        Path dir = derivedDir(musicId);
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("删除派生音频失败: {}", path, e);
                }
            });
        } catch (IOException e) {
            log.warn("列出派生音频失败: {}", dir, e);
        }
    }

    /** 防止路径逃逸：仅当 {@code file} 规范化后位于 {@code expectedDir} 下时返回 true。 */
    public static boolean isUnderDirectory(Path file, Path expectedDir) {
        Path base = expectedDir.toAbsolutePath().normalize();
        Path p = file.toAbsolutePath().normalize();
        return p.startsWith(base);
    }

    /**
     * 形如 {@code 12.mp3}（id 后仅一段扩展名），排除 {@code 12.a.mp3} 等。
     */
    static boolean isSingleSegmentExtension(String fileName, String idPrefix) {
        if (!fileName.startsWith(idPrefix)) {
            return false;
        }
        String rest = fileName.substring(idPrefix.length());
        return !rest.isEmpty() && !rest.contains(".");
    }

    /**
     * 定位 {@code {id}.<ext>}：按扩展名优先级逐个直接探测，不做全目录扫描。
     *
     * <p>{@code Music} 有数万个条目，先前每次请求都 {@code Files.list} 整个目录，
     * 在并发下（目录不在页缓存时要真读盘）会放大到秒级；
     * 直接拼路径后正常情况只需几次 {@code stat}。</p>
     *
     */
    static Optional<Path> findIdNamedFile(Path dir, int musicId, List<String> extPriority)
            throws IOException {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        String idPrefix = musicId + ".";
        for (String ext : extPriority) {
            Path candidate = dir.resolve(idPrefix + ext);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> pickOne(List<Path> candidates, List<String> extPriority) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Path> byExt = new HashMap<>();
        for (Path p : candidates) {
            String name = p.getFileName().toString();
            int dot = name.lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            byExt.putIfAbsent(ext, p);
        }
        for (String pref : extPriority) {
            if (byExt.containsKey(pref)) {
                return Optional.of(byExt.get(pref));
            }
        }
        return Optional.empty();
    }

    private static void deleteIdNamedFiles(Path dir, int musicId, String kind) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        String prefix = musicId + ".";
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> isSingleSegmentExtension(p.getFileName().toString(), prefix))
                    .forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("删除{}文件失败 musicId={} path={}: {}", kind, musicId, p, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("列出{}目录失败 musicId={}: {}", kind, musicId, e.toString());
        }
    }

    private static void deleteIdNamedFilesExcept(Path dir, int musicId, Path keep, String kind) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        Path keepNormalized = keep == null ? null : keep.toAbsolutePath().normalize();
        String prefix = musicId + ".";
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> isSingleSegmentExtension(p.getFileName().toString(), prefix))
                    .filter(p -> keepNormalized == null
                            || !p.toAbsolutePath().normalize().equals(keepNormalized))
                    .forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("删除{}文件失败 musicId={} path={}: {}", kind, musicId, p, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("列出{}目录失败 musicId={}: {}", kind, musicId, e.toString());
        }
    }

    private static void deleteCoverVariantsExcept(int musicId, Path keep) {
        Path dir = coverDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        Path keepNormalized = keep.toAbsolutePath().normalize();
        String prefix = musicId + ".";
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> isSingleSegmentExtension(path.getFileName().toString(), prefix))
                    .filter(path -> !path.toAbsolutePath().normalize().equals(keepNormalized))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.warn("删除旧封面失败 musicId={} path={}: {}", musicId, path, e.toString());
                        }
                    });
        } catch (IOException e) {
            log.warn("列出封面目录失败 musicId={}: {}", musicId, e.toString());
        }
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
