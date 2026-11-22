package com.neko.music.service;

import com.neko.music.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 曲目可播放状态。
 *
 * <p>状态单独存放在 {@code music_playability}，没有记录时默认允许播放。关闭播放时，
 * 在同一事务内移除所有用户的收藏和歌单条目，并刷新受影响歌单的计数。</p>
 */
public class MusicPlayabilityService {
    private static final Logger logger = LoggerFactory.getLogger(MusicPlayabilityService.class);

    private final DatabaseManager databaseManager;

    public MusicPlayabilityService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public record ChangeResult(
            boolean playable,
            int removedFavorites,
            int removedPlaylistEntries,
            int affectedPlaylists
    ) {
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

                int removedFavorites = 0;
                int removedPlaylistEntries = 0;
                int affectedPlaylists = 0;
                if (!playable) {
                    List<Integer> playlistIds = findAffectedPlaylists(conn, musicId);
                    affectedPlaylists = playlistIds.size();

                    try (PreparedStatement stmt = conn.prepareStatement(
                            "DELETE FROM user_favorites WHERE music_id = ?")) {
                        stmt.setInt(1, musicId);
                        removedFavorites = stmt.executeUpdate();
                    }
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "DELETE FROM playlist_music WHERE music_id = ?")) {
                        stmt.setInt(1, musicId);
                        removedPlaylistEntries = stmt.executeUpdate();
                    }
                    updatePlaylistCounts(conn, playlistIds);
                }

                conn.commit();
                logger.info("更新曲目播放状态 musicId={} playable={} removedFavorites={} "
                                + "removedPlaylistEntries={} affectedPlaylists={}",
                        musicId, playable, removedFavorites, removedPlaylistEntries, affectedPlaylists);
                return new ChangeResult(playable, removedFavorites, removedPlaylistEntries, affectedPlaylists);
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

    private static List<Integer> findAffectedPlaylists(Connection conn, int musicId) throws SQLException {
        List<Integer> ids = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT DISTINCT playlist_id FROM playlist_music WHERE music_id = ?")) {
            stmt.setInt(1, musicId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getInt(1));
                }
            }
        }
        return ids;
    }

    private static void updatePlaylistCounts(Connection conn, List<Integer> playlistIds) throws SQLException {
        if (playlistIds.isEmpty()) {
            return;
        }
        try (PreparedStatement stmt = conn.prepareStatement("""
                UPDATE playlists SET music_count =
                    (SELECT COUNT(*) FROM playlist_music WHERE playlist_id = ?)
                WHERE id = ?
                """)) {
            for (int playlistId : playlistIds) {
                stmt.setInt(1, playlistId);
                stmt.setInt(2, playlistId);
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }
}
