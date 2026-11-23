package com.neko.music.service;

import com.neko.music.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 曲目可播放状态。
 *
 * <p>状态单独存放在 {@code music_playability}，没有记录时默认允许播放。播放状态只影响
 * 客户端和媒体入口的可用性，不删除用户收藏、歌单或歌单曲目，避免运营侧开关造成用户数据丢失。</p>
 */
public class MusicPlayabilityService {
    private static final Logger logger = LoggerFactory.getLogger(MusicPlayabilityService.class);

    private final DatabaseManager databaseManager;

    public MusicPlayabilityService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public record ChangeResult(boolean playable) {
    }

    public boolean isPlayable(int musicId) {
        try (Connection conn = databaseManager.getConnection()) {
            return isPlayable(conn, musicId);
        } catch (SQLException e) {
            logger.warn("读取曲目播放状态失败，按可播放处理 musicId={}: {}", musicId, e.getMessage());
            return true;
        }
    }

    public static boolean isPlayable(Connection conn, int musicId) throws SQLException {
        String sql = "SELECT COALESCE("
                + "(SELECT playable FROM music_playability WHERE music_id = ?), TRUE)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, musicId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /**
     * 用于公开查询的 SQL 条件；{@code alias} 只能是代码内写死的表别名。
     */
    public static String playableCondition(String alias) {
        return "NOT EXISTS (SELECT 1 FROM music_playability mp_filter "
                + "WHERE mp_filter.music_id = " + alias + ".id AND mp_filter.playable = FALSE)";
    }

    /**
     * 返回某张曲目当前播放状态的 SQL 表达式。
     *
     * <p>用于搜索结果、歌单曲目等需要同时返回“是否存在禁播标记”的场景。{@code alias}
     * 只能使用代码内写死的表别名，不能拼接请求参数。</p>
     */
    public static String playableSelect(String alias) {
        return "COALESCE((SELECT mp_state.playable FROM music_playability mp_state "
                + "WHERE mp_state.music_id = " + alias + ".id), TRUE)";
    }

    public ChangeResult setPlayable(int musicId, boolean playable) throws SQLException {
        try (Connection conn = databaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!musicExists(conn, musicId)) {
                    throw new IllegalArgumentException("music.not_found");
                }

                int updated;
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE music_playability SET playable = ? WHERE music_id = ?")) {
                    stmt.setBoolean(1, playable);
                    stmt.setInt(2, musicId);
                    updated = stmt.executeUpdate();
                }
                if (updated == 0) {
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "INSERT INTO music_playability (music_id, playable) VALUES (?, ?)")) {
                        stmt.setInt(1, musicId);
                        stmt.setBoolean(2, playable);
                        stmt.executeUpdate();
                    }
                }

                conn.commit();
                logger.info("更新曲目播放状态 musicId={} playable={}（保留收藏和歌单数据）",
                        musicId, playable);
                return new ChangeResult(playable);
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof SQLException sqlException) {
                    throw sqlException;
                }
                if (e instanceof IllegalArgumentException illegalArgumentException) {
                    throw illegalArgumentException;
                }
                throw new SQLException("更新曲目播放状态失败", e);
            }
        }
    }

    private static boolean musicExists(Connection conn, int musicId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM music WHERE id = ?")) {
            stmt.setInt(1, musicId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

}
