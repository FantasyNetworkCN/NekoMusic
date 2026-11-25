package com.neko.music.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 曲目封面读取入口：从 {@code Music/{id}.*} 的内嵌封面解析并做进程内缓存。
 *
 * <p>缓存以音频文件的 {@code mtime + size} 为版本：音频被替换（含内嵌封面重写）时自动失效，
 * 无需显式清理；按字节预算与访问顺序逐项淘汰，内存占用有界。封面字节同时给出 SHA-256，用于 HTTP
 * ETag——这样只改标题/歌词（音频 mtime 变了）不会让前端封面缓存失效。</p>
 */
public final class CoverArtCache {
    private static final Logger logger = LoggerFactory.getLogger(CoverArtCache.class);

    /** 控制大曲库预热/访问时的最大常驻封面内存。 */
    private static final long MAX_CACHED_BYTES = 128L * 1024 * 1024;

    private CoverArtCache() {
    }

    /** 对外暴露的封面快照。 */
    public record Snapshot(byte[] data, String mime, String etag) {
    }

    private record Entry(Snapshot snapshot, String sourceVersion) {
    }

    /** 缓存封面字节，响应时直接写出，避免每次打开音频、解析标签或重复算哈希。 */
    private static final WeightedLoadingCache<Path, Entry> CACHE =
            new WeightedLoadingCache<>(4096, MAX_CACHED_BYTES, entry -> entry.snapshot().data().length);

    /** 读取封面；曲库无音频或音频内无封面返回 empty（调用方回默认图）。 */
    public static Optional<Snapshot> find(int musicId) {
        if (musicId <= 0) {
            return Optional.empty();
        }
        Optional<Path> sidecar = MusicAssetLocator.findCoverSidecar(musicId);
        if (sidecar.isPresent()) {
            Optional<Snapshot> image = findImage(sidecar.get());
            if (image.isPresent()) {
                return image;
            }
        }
        Optional<Path> audio = MusicAssetLocator.findAudioFile(musicId);
        if (audio.isEmpty()) {
            return Optional.empty();
        }
        return find(audio.get());
    }

    /** 读取指定音频文件的封面。 */
    public static Optional<Snapshot> find(Path audioFile) {
        int musicId = musicIdFromAudioPath(audioFile);
        if (musicId > 0) {
            Optional<Path> sidecar = MusicAssetLocator.findCoverSidecar(musicId);
            if (sidecar.isPresent()) {
                Optional<Snapshot> image = findImage(sidecar.get());
                if (image.isPresent()) {
                    return image;
                }
            }
        }
        String version;
        try {
            BasicFileAttributes attrs = Files.readAttributes(audioFile, BasicFileAttributes.class);
            version = attrs.lastModifiedTime().toMillis() + "-" + attrs.size();
        } catch (IOException e) {
            return Optional.empty();
        }
        Path key = audioFile.toAbsolutePath().normalize();
        Entry entry = CACHE.get(key, cached -> version.equals(cached.sourceVersion()), () ->
                EmbeddedCoverReader.read(audioFile)
                        .map(cover -> new Entry(
                                new Snapshot(cover.data(), cover.mime(), sha256(cover.data())), version))
                        .orElse(null));
        return Optional.ofNullable(entry).map(Entry::snapshot);
    }

    private static Optional<Snapshot> findImage(Path imageFile) {
        String version;
        try {
            BasicFileAttributes attrs = Files.readAttributes(imageFile, BasicFileAttributes.class);
            version = attrs.lastModifiedTime().toMillis() + "-" + attrs.size();
        } catch (IOException e) {
            return Optional.empty();
        }
        Path key = imageFile.toAbsolutePath().normalize();
        Entry entry = CACHE.get(key, cached -> version.equals(cached.sourceVersion()), () -> {
            try {
                byte[] data = Files.readAllBytes(imageFile);
                if (data.length == 0) {
                    return null;
                }
                String mime = EmbeddedCoverReader.sniffMime(data);
                if (mime == null) {
                    mime = "image/jpeg";
                }
                return new Entry(new Snapshot(data, mime, sha256(data)), version);
            } catch (IOException e) {
                return null;
            }
        });
        return Optional.ofNullable(entry).map(Entry::snapshot);
    }

    /** 曲目更新后（换音频/换封面）主动失效，避免同一 mtime 分辨率内的竞态。 */
    public static void invalidate(Path audioFile) {
        if (audioFile != null) {
            CACHE.invalidate(audioFile.toAbsolutePath().normalize());
            int musicId = musicIdFromAudioPath(audioFile);
            if (musicId > 0) {
                MusicAssetLocator.findCoverSidecar(musicId)
                        .ifPresent(path -> CACHE.invalidate(path.toAbsolutePath().normalize()));
            }
        }
    }

    /** 曲目删除时清理缓存。 */
    public static void invalidate(int musicId) {
        String prefix = musicId + ".";
        Path coverDir = MusicAssetLocator.coverDir(musicId).toAbsolutePath().normalize();
        CACHE.invalidateIf(key -> {
            Path name = key.getFileName();
            if (name == null) {
                return false;
            }
            if (name.toString().startsWith(prefix)) {
                return true;
            }
            Path parent = key.getParent();
            return parent != null && parent.equals(coverDir);
        });
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception e) {
            logger.debug("计算封面哈希失败: {}", e.toString());
            return Integer.toHexString(java.util.Arrays.hashCode(data));
        }
    }

    private static int musicIdFromAudioPath(Path audioFile) {
        if (audioFile == null) {
            return 0;
        }
        Path name = audioFile.getFileName();
        if (name == null) {
            return 0;
        }
        String value = name.toString();
        int dot = value.indexOf('.');
        if (dot <= 0) {
            return 0;
        }
        try {
            return Integer.parseInt(value.substring(0, dot));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
