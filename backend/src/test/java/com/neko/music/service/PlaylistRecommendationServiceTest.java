package com.neko.music.service;

import com.neko.music.config.ConfigManager;
import com.neko.music.database.DatabaseManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlaylistRecommendationServiceTest {
    @TempDir
    Path directory;
    private DatabaseManager database;
    private PlaylistRecommendationService service;

    @BeforeEach
    void createCatalog() throws Exception {
        database = new DatabaseManager(new ConfigManager()) {
            @Override
            public Connection getConnection() throws SQLException {
                return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("catalog.db"));
            }
        };
        service = new PlaylistRecommendationService(database);
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE playlists (id INTEGER PRIMARY KEY, user_id INTEGER, name TEXT,
                      description TEXT, music_count INTEGER, created_at TIMESTAMP, updated_at TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE music (id INTEGER PRIMARY KEY, title TEXT, artist TEXT,
                      language TEXT, tags TEXT, play_count INTEGER)
                    """);
            statement.execute("CREATE TABLE playlist_music (playlist_id INTEGER, music_id INTEGER, position INTEGER)");
            statement.execute("CREATE TABLE user_favorite_playlists (playlist_id INTEGER)");
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            try (var playlist = connection.prepareStatement("INSERT INTO playlists VALUES (?, ?, ?, ?, ?, ?, ?)");
                 var music = connection.prepareStatement("INSERT INTO music VALUES (?, ?, ?, ?, ?, ?)");
                 var membership = connection.prepareStatement("INSERT INTO playlist_music VALUES (?, ?, ?)")) {
                for (int id = 1; id <= 8; id++) {
                    playlist.setInt(1, id);
                    playlist.setInt(2, id % 3);
                    playlist.setString(3, "歌单" + id);
                    playlist.setString(4, "流式聚合测试 " + id);
                    playlist.setInt(5, 45);
                    Timestamp date = Timestamp.from(today.minusDays(id).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
                    playlist.setTimestamp(6, date);
                    playlist.setTimestamp(7, date);
                    playlist.executeUpdate();
                    for (int position = 1; position <= 45; position++) {
                        int musicId = id * 100 + position;
                        music.setInt(1, musicId);
                        music.setString(2, ("歌曲" + position + " ").repeat(30));
                        music.setString(3, position % 4 == 0 ? null : "歌手" + position % 3);
                        music.setString(4, position % 2 == 0 ? "日语" : "华语");
                        music.setString(5, position % 5 == 0 ? null : "rock|live，jazz; ROCK");
                        music.setInt(6, (id * 113 + position) % 77);
                        music.executeUpdate();
                        membership.setInt(1, id);
                        membership.setInt(2, musicId);
                        membership.setInt(3, position);
                        membership.executeUpdate();
                    }
                    for (int i = 0; i < id % 3; i++) {
                        statement.execute("INSERT INTO user_favorite_playlists VALUES (" + id + ")");
                    }
                }
            }
        }
    }

    @Test
    void aggregatesCurrentRowsWithoutChangingResponseOrderOrCovers() {
        List<PlaylistRecommendationService.RecommendedPlaylist> result = snapshot();
        assertEquals(5, result.size());
        // 改造前同一份数据库的返回顺序，锁定逐行聚合后的排序与多样性选择。
        assertEquals(List.of(1, 3, 5, 7, 8), result.stream()
                .map(PlaylistRecommendationService.RecommendedPlaylist::id).toList());
        for (var playlist : result) {
            assertEquals(45, playlist.musicCount());
            assertEquals(List.of(
                    "/api/music/cover/" + (playlist.id() * 100 + 1),
                    "/api/music/cover/" + (playlist.id() * 100 + 2),
                    "/api/music/cover/" + (playlist.id() * 100 + 3),
                    "/api/music/cover/" + (playlist.id() * 100 + 4)), playlist.coverUrls());
            assertEquals("popular", playlist.source());
            assertFalse(playlist.exploration());
        }
    }

    @Test
    void nextRequestImmediatelySeesChangedPlaylistAndSongs() throws Exception {
        snapshot();
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("UPDATE playlists SET name = '更新后的歌单', music_count = 99 WHERE id = 8");
            statement.execute("DELETE FROM playlist_music WHERE playlist_id = 8");
            for (int i = 1; i <= 3; i++) {
                statement.execute("INSERT INTO music VALUES (" + (9000 + i)
                        + ", '新歌曲', '新歌手', '华语', 'jazz', 1000000000)");
                statement.execute("INSERT INTO playlist_music VALUES (8, " + (9000 + i) + ", " + i + ")");
            }
        }
        var updated = snapshot().stream().filter(playlist -> playlist.id() == 8).findFirst().orElseThrow();
        assertEquals("更新后的歌单", updated.name());
        assertEquals(99, updated.musicCount());
        assertEquals(List.of("/api/music/cover/9001", "/api/music/cover/9002", "/api/music/cover/9003"),
                updated.coverUrls());
    }

    List<PlaylistRecommendationService.RecommendedPlaylist> snapshot() {
        return service.recommend(null, false);
    }
}
