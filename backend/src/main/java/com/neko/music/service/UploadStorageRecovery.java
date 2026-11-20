package com.neko.music.service;

import com.neko.music.database.DatabaseManager;
import com.neko.music.util.MusicAssetLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 启动时修复审核文件与数据库之间因重启留下的半完成状态。
 *
 * <p>审核通过现在会在数据库提交后才删上传源文件，因此任意时刻重启都不会丢源文件；本类负责清理
 * 提交后尚未删掉的副本，以及事务回滚后没有数据库记录的正式文件。
 * 只有在成功读取数据库记录后才按引用关系清理，数据库不可用时只清理明确无用的暂存文件。</p>
 */
public final class UploadStorageRecovery {
    private static final Logger logger = LoggerFactory.getLogger(UploadStorageRecovery.class);

    static final String UPLOAD_DIR = "user_upload";
    private static final String AUDIO_DIR = MusicAssetLocator.AUDIO_REL_DIR;
    private static final String STAGING_PREFIX = ".neko-staging-";
    private static final Pattern ID_NAMED_FILE = Pattern.compile("^(\\d+)\\.[^.]+$");
    /** 滚动重启时新旧实例可能短暂并存，只清理已静止一段时间的残留。 */
    private static final long RECOVERY_GRACE_MILLIS = 10 * 60_000L;

    private UploadStorageRecovery() {
    }

    /** 入口：任何恢复失败都只记日志，不能阻止后端启动。 */
    public static void recover(DatabaseManager databaseManager) {
        long cutoffMillis = System.currentTimeMillis() - RECOVERY_GRACE_MILLIS;
        List<StoredUpload> uploads = loadUploads(databaseManager);
        if (uploads != null) {
            Set<Path> pendingFiles = pendingUploadPaths(uploads);
            deleteUnreferencedUploadFiles(Paths.get(UPLOAD_DIR), pendingFiles, cutoffMillis);
        }

        Set<Integer> musicIds = loadMusicIds(databaseManager);
        cleanupMediaDir(Paths.get(AUDIO_DIR), musicIds, cutoffMillis);
    }

    private static Set<Path> pendingUploadPaths(List<StoredUpload> uploads) {
        Set<Path> paths = new HashSet<>();
        for (StoredUpload upload : uploads) {
            if ("pending".equals(upload.status())) {
                addNormalized(paths, upload.musicFilePath());
                addNormalized(paths, upload.coverFilePath());
                addNormalized(paths, upload.lyricsFilePath());
            }
        }
        return paths;
    }

    private static void addNormalized(Set<Path> paths, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return;
        }
        try {
            paths.add(Paths.get(rawPath).toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            logger.warn("忽略无法解析的上传路径: {}", rawPath);
        }
    }

    /** 删除上传目录中不再被任何待审核记录引用的文件。 */
    static void deleteUnreferencedUploadFiles(Path uploadDir, Set<Path> keepPaths, long cutoffMillis) {
        if (!Files.isDirectory(uploadDir)) {
            return;
        }
        int removed = 0;
        try (Stream<Path> files = Files.list(uploadDir)) {
            List<Path> candidates = files.filter(Files::isRegularFile).toList();
            for (Path file : candidates) {
                Path normalized = file.toAbsolutePath().normalize();
                if (!keepPaths.contains(normalized) && isOlderThan(file, cutoffMillis) && deleteQuietly(file)) {
                    removed++;
                }
            }
        } catch (IOException e) {
            logger.warn("扫描上传目录失败: {}", uploadDir, e);
        }
        if (removed > 0) {
            logger.info("已清理 {} 个无数据库引用的上传文件", removed);
        }
    }

    /**
     * 清理媒体目录：始终删除暂存文件；若能读到音乐表，再删除没有对应 music 行的
     * {@code {id}.{ext}} 孤儿文件。
     */
    static void cleanupMediaDir(Path dir, Set<Integer> knownMusicIds, long cutoffMillis) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        int removed = 0;
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> candidates = files.filter(Files::isRegularFile).toList();
            for (Path file : candidates) {
                String name = file.getFileName().toString();
                boolean stagingFile = name.startsWith(STAGING_PREFIX);
                boolean orphanMedia = knownMusicIds != null && isOrphanIdNamedFile(name, knownMusicIds);
                if ((stagingFile || orphanMedia) && isOlderThan(file, cutoffMillis) && deleteQuietly(file)) {
                    removed++;
                }
            }
        } catch (IOException e) {
            logger.warn("扫描媒体目录失败: {}", dir, e);
        }
        if (removed > 0) {
            logger.info("已清理 {} 个审核残留文件: {}", removed, dir);
        }
    }

    private static boolean isOrphanIdNamedFile(String name, Set<Integer> knownMusicIds) {
        Matcher matcher = ID_NAMED_FILE.matcher(name);
        if (!matcher.matches()) {
            return false;
        }
        try {
            return !knownMusicIds.contains(Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean deleteQuietly(Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.warn("删除残留文件失败: {}", path, e);
            return false;
        }
    }

    private static boolean isOlderThan(Path path, long cutoffMillis) {
        try {
            return Files.getLastModifiedTime(path).toMillis() < cutoffMillis;
        } catch (IOException e) {
            logger.warn("读取残留文件时间失败，跳过清理: {}", path, e);
            return false;
        }
    }

    private static List<StoredUpload> loadUploads(DatabaseManager databaseManager) {
        String sql = """
            SELECT id, status, music_file_path, cover_file_path, lyrics_file_path
            FROM user_uploads
            """;
        List<StoredUpload> uploads = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                uploads.add(new StoredUpload(
                        rs.getInt("id"),
                        rs.getString("status"),
                        rs.getString("music_file_path"),
                        rs.getString("cover_file_path"),
                        rs.getString("lyrics_file_path")));
            }
            return uploads;
        } catch (SQLException e) {
            logger.warn("读取待恢复的上传记录失败，跳过上传目录清理", e);
            return null;
        }
    }

    private static Set<Integer> loadMusicIds(DatabaseManager databaseManager) {
        Set<Integer> ids = new HashSet<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement("SELECT id FROM music");
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getInt(1));
            }
            return ids;
        } catch (SQLException e) {
            logger.warn("读取音乐记录失败，本次只清理暂存文件", e);
            return null;
        }
    }

    private record StoredUpload(int id, String status, String musicFilePath,
                                String coverFilePath, String lyricsFilePath) {
    }
}
