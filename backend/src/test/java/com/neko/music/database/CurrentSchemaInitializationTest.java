package com.neko.music.database;

import com.neko.music.config.ConfigManager;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class CurrentSchemaInitializationTest {
    @Test
    void freshSchemasContainCurrentColumnsWithoutUpgradingExistingData() throws Exception {
        List<String> commands = new ArrayList<>();
        Connection connection = recordingConnection(commands);
        HikariDataSource pool = new HikariDataSource() {
            @Override
            public Connection getConnection() {
                return connection;
            }
        };
        try {
            DatabaseManager main = new DatabaseManager(new ConfigManager()) {
                @Override
                public Connection getConnection() {
                    return connection;
                }
            };
            var mainPool = DatabaseManager.class.getDeclaredField("dataSource");
            mainPool.setAccessible(true);
            mainPool.set(main, pool);
            var initMain = DatabaseManager.class.getDeclaredMethod("initializeTables");
            initMain.setAccessible(true);
            initMain.invoke(main);
            DatabaseInitializer.initializeTables(main);

            LogDatabaseManager logs = new LogDatabaseManager(new ConfigManager(), "test_logs");
            var logPool = LogDatabaseManager.class.getDeclaredField("dataSource");
            logPool.setAccessible(true);
            logPool.set(logs, pool);
            var initLogs = LogDatabaseManager.class.getDeclaredMethod("initializeTables");
            initLogs.setAccessible(true);
            initLogs.invoke(logs);

            assertFalse(commands.isEmpty());
            for (String sql : commands) {
                assertTrue(sql.stripLeading().startsWith("CREATE TABLE IF NOT EXISTS "), sql);
                assertFalse(sql.toUpperCase(Locale.ROOT).contains("ALTER TABLE"), sql);
            }
            String music = table(commands, "music");
            for (String column : List.of("max_quality", "bitrate_bps", "sample_rate_hz",
                    "bits_per_sample", "channels", "play_count", "title_pinyin",
                    "artist_pinyin", "album_pinyin", "title_word_initials", "artist_word_initials")) {
                assertTrue(music.contains(column), column);
            }
            String users = table(commands, "users");
            assertTrue(users.contains("auth_user_id"));
            assertTrue(users.contains("vip_expires_at"));
            assertTrue(users.contains("uk_users_auth_user_id"));
            assertFalse(users.contains("password"));
            assertFalse(users.contains("nickname"));
            assertFalse(users.contains("avatar"));
            assertTrue(table(commands, "playlists").contains("idx_playlists_name_pinyin"));
            assertTrue(table(commands, "music_comments").contains("client_ip"));
            assertFalse(table(commands, "music_comments").contains("deleted"));
            assertTrue(table(commands, "app_release").contains("pending_effective_at"));
            String access = table(commands, "access_log");
            for (String column : List.of("duration_us", "seo_render", "user_name",
                    "threat_engine_version", "idx_access_occurred_path", "idx_access_occurred_user")) {
                assertTrue(access.contains(column), column);
            }
            assertFalse(commands.stream().anyMatch(sql -> sql.contains("CREATE TABLE IF NOT EXISTS admins ")));
        } finally {
            pool.close();
        }
    }

    private static String table(List<String> commands, String name) {
        return commands.stream().filter(sql -> sql.contains("CREATE TABLE IF NOT EXISTS " + name + " ("))
                .findFirst().orElseThrow();
    }

    private static Connection recordingConnection(List<String> commands) {
        Statement statement = (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(),
                new Class<?>[]{Statement.class}, (proxy, method, args) -> {
                    if (method.getName().equals("execute")) {
                        commands.add((String) args[0]);
                        return false;
                    }
                    if (method.getName().equals("close")) {
                        return null;
                    }
                    throw new AssertionError("初始化不应执行数据读写或升级: " + method.getName());
                });
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "createStatement" -> statement;
                    case "close" -> null;
                    default -> throw new AssertionError("初始化不应执行数据读写或升级: " + method.getName());
                });
    }
}
