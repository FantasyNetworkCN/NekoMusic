package com.neko.music.service;

import com.neko.music.database.DatabaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicPlayabilityServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void disablingPlaybackDeletesFavoritesAndPlaylistEntriesAndUpdatesCounts() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("playability.db");
        createSchema(url);
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO music (id) VALUES (7)");
            stmt.execute("INSERT INTO user_favorites (user_id, music_id) VALUES (1, 7), (2, 7)");
            stmt.execute("INSERT INTO playlists (id, music_count) VALUES (10, 1), (11, 1)");
            stmt.execute("INSERT INTO playlist_music (playlist_id, music_id, position) VALUES (10, 7, 1), (11, 7, 1)");
        }

        MusicPlayabilityService service = new MusicPlayabilityService(new SqliteDatabaseManager(url));
        assertTrue(service.isPlayable(7));

        MusicPlayabilityService.ChangeResult result = service.setPlayable(7, false);

        assertFalse(service.isPlayable(7));
        assertEquals(2, result.removedFavorites());
        assertEquals(2, result.removedPlaylistEntries());
        assertEquals(2, result.affectedPlaylists());
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {
            assertEquals(0, count(stmt, "SELECT COUNT(*) FROM user_favorites WHERE music_id = 7"));
            assertEquals(0, count(stmt, "SELECT COUNT(*) FROM playlist_music WHERE music_id = 7"));
            assertEquals(0, count(stmt, "SELECT SUM(music_count) FROM playlists"));
        }

        MusicPlayabilityService.ChangeResult enabled = service.setPlayable(7, true);
        assertTrue(service.isPlayable(7));
        assertEquals(0, enabled.removedFavorites());
        assertEquals(0, enabled.removedPlaylistEntries());
    }

    private static void createSchema(String url) throws SQLException {
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE music (id INTEGER PRIMARY KEY)");
            stmt.execute("CREATE TABLE music_playability ("
                    + "music_id INTEGER PRIMARY KEY, playable BOOLEAN NOT NULL DEFAULT TRUE,"
                    + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            stmt.execute("CREATE TABLE user_favorites (user_id INTEGER, music_id INTEGER)");
            stmt.execute("CREATE TABLE playlists (id INTEGER PRIMARY KEY, music_count INTEGER NOT NULL DEFAULT 0)");
            stmt.execute("CREATE TABLE playlist_music ("
                    + "playlist_id INTEGER, music_id INTEGER, position INTEGER)");
        }
    }

    private static int count(Statement stmt, String sql) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static final class SqliteDatabaseManager extends DatabaseManager {
        private final String url;

        private SqliteDatabaseManager(String url) {
            super(null);
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }
    }
}
