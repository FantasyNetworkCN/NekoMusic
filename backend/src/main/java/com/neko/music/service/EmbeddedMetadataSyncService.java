package com.neko.music.service;

import com.neko.music.Main;
import com.neko.music.database.LyricsDatabaseManager;
import com.neko.music.util.MusicAdMetadataPatcher;
import com.neko.music.util.MusicAssetLocator;
import com.neko.music.util.CoverArtCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * 把曲库（数据库）中的标题 / 艺术家 / 专辑 / 歌词 / 封面写进音频文件标签，广告元数据由
 * {@link MusicAdMetadataPatcher} 一并保留。
 *
 * <p>入库与编辑（歌词、封面、标题等）后由各处理器调用 {@link #syncOne(int)}；
 * 写失败只记日志，不影响主流程。
 */
public final class EmbeddedMetadataSyncService {
    private static final Logger logger = LoggerFactory.getLogger(EmbeddedMetadataSyncService.class);

    private EmbeddedMetadataSyncService() {
    }

    /**
     * 同步单首音乐的内嵌元数据；曲库无记录、磁盘无文件或写入失败都返回 false，不抛异常。
     */
    public static boolean syncOne(int musicId) {
        return syncOne(musicId, null);
    }

    /**
     * 同步曲库元数据，并可选地把调用方提供的临时封面保存为独立封面文件。
     * 同时尝试写入音频标签；容器不支持标签时不影响独立封面与上传结果。
     */
    public static boolean syncOne(int musicId, Path coverFile) {
        if (musicId <= 0) {
            return false;
        }
        Optional<Path> audio = MusicAssetLocator.findAudioFile(musicId);
        if (audio.isEmpty()) {
            return false;
        }
        boolean metadataSynced = false;
        boolean coverSaved = false;
        try {
            MusicRow row = loadRow(musicId);
            if (row == null) {
                return false;
            }
            MusicAdMetadataPatcher.LibraryMetadata metadata = new MusicAdMetadataPatcher.LibraryMetadata(
                    row.title(), row.artist(), row.album(),
                    loadLyricsBody(musicId), coverFile);
            MusicAdMetadataPatcher.syncLibraryMetadata(audio.get(), metadata);
            metadataSynced = true;
        } catch (Exception e) {
            logger.warn("同步内嵌元数据失败，将保留独立封面 musicId={}: {}", musicId, e.toString());
        } finally {
            // 即使标签写入报错，也可能已触碰文件；下次请求必须重新检查封面版本。
            CoverArtCache.invalidate(audio.get());
        }
        if (coverFile != null) {
            try {
                MusicAssetLocator.replaceCoverSidecar(musicId, coverFile);
                coverSaved = true;
            } catch (Exception e) {
                logger.warn("保存独立封面失败 musicId={}: {}", musicId, e.toString());
            }
        }
        CoverArtCache.invalidate(musicId);
        return metadataSynced || coverSaved;
    }

    private static MusicRow loadRow(int musicId) throws SQLException {
        String sql = "SELECT id, title, artist, album FROM music WHERE id = ?";
        try (Connection conn = Main.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, musicId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new MusicRow(rs.getInt("id"), rs.getString("title"),
                        rs.getString("artist"), rs.getString("album"));
            }
        }
    }

    /** 歌词正文：占位歌词与空歌词都视为「无正文」，只保留广告横幅。 */
    private static String loadLyricsBody(int musicId) {
        LyricsDatabaseManager lyricsDatabaseManager = Main.getLyricsDatabaseManager();
        if (lyricsDatabaseManager == null) {
            return "";
        }
        return lyricsDatabaseManager.findByMusicId(musicId)
                .filter(stored -> !stored.placeholder())
                .map(LyricsDatabaseManager.StoredLyrics::lrcContent)
                .orElse("");
    }

    private record MusicRow(int id, String title, String artist, String album) {
    }
}
