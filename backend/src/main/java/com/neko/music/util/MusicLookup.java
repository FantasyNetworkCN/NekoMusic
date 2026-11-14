package com.neko.music.util;

import com.neko.music.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;

/**
 * 音乐行查询辅助。
 *
 * <p>原先 {@code MusicCoverHandler} 与 {@code MusicFileHandler} 各复制了一份
 * {@code musicRowExists(int)}，收敛到这里。
 */
public final class MusicLookup {

    private static final Logger logger = LoggerFactory.getLogger(MusicLookup.class);

    private MusicLookup() {
    }

    /** 判断 music 表中是否存在指定 id 的记录。 */
    public static boolean musicRowExists(int musicId) {
        try (Connection conn = Main.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM music WHERE id = ? LIMIT 1")) {
            stmt.setInt(1, musicId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            logger.error("校验音乐记录时出错，音乐ID: {}", musicId, e);
            return false;
        }
    }

    /** 查询曲目最高音质（standard/hq/sq/hires）；不存在或未探测过返回 null。 */
    public static String findMaxQuality(int musicId) {
        try (Connection conn = Main.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT max_quality FROM music WHERE id = ?")) {
            stmt.setInt(1, musicId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString("max_quality") : null;
            }
        } catch (Exception e) {
            logger.error("查询音乐音质时出错，音乐ID: {}", musicId, e);
            return null;
        }
    }

    /** 一起听临时歌单需要的轻量曲目信息；不存在或查询失败时返回空。 */
    public static Optional<com.neko.music.service.ListenTogetherService.Track> findListenTrack(int musicId) {
        String sql = "SELECT id, title, artist, album, duration, max_quality FROM music WHERE id = ?";
        try (Connection conn = Main.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, musicId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new com.neko.music.service.ListenTogetherService.Track(
                        rs.getInt("id"),
                        rs.getString("title") == null ? "" : rs.getString("title"),
                        rs.getString("artist") == null ? "" : rs.getString("artist"),
                        rs.getString("album") == null ? "" : rs.getString("album"),
                        Math.max(0, rs.getInt("duration")),
                        rs.getString("max_quality")));
            }
        } catch (Exception e) {
            logger.error("查询一起听曲目信息失败，音乐ID: {}", musicId, e);
            return Optional.empty();
        }
    }
}
