package com.neko.music.database;

import com.neko.music.config.ConfigManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private HikariDataSource dataSource;
    private ConfigManager configManager;

    public DatabaseManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public void init() throws SQLException {
        init(configManager.getHikariMaximumPoolSize(), configManager.getHikariMinimumIdle());
    }

    /** 启动引导阶段只需串行读取建表与系统设置，避免先按出厂值预建大量连接。 */
    public void initBootstrap() throws SQLException {
        init(1, 0);
    }

    private void init(int maximumPoolSize, int minimumIdle) throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + configManager.getMysqlHost() + ":" +
                         configManager.getMysqlPort() + "/" + configManager.getMysqlDatabase() +
                         "?useSSL=false&serverTimezone=Asia/Shanghai&connectionTimeZone=Asia/Shanghai" +
                         "&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true" +
                         "&cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048&useServerPrepStmts=true");
        config.setUsername(configManager.getMysqlUsername());
        config.setPassword(configManager.getMysqlPassword());
        config.setMaximumPoolSize(maximumPoolSize);
        config.setMinimumIdle(Math.min(minimumIdle, maximumPoolSize));
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        
        // 设置连接初始化SQL，确保每次连接都使用UTC+8时区
        config.setConnectionInitSql("SET time_zone = '+08:00'");
        
        this.dataSource = new HikariDataSource(config);
        
        // 初始化数据库表
        try {
            initializeTables();
        } catch (SQLException | RuntimeException e) {
            dataSource.close();
            throw e;
        }
    }

    /** 仅在启动时、加载 system_settings 后调用；后台保存仍遵循重启生效约定。 */
    public void applyRuntimePoolSettings() {
        int maximum = configManager.getHikariMaximumPoolSize();
        int minimum = Math.min(configManager.getHikariMinimumIdle(), maximum);
        var poolConfig = dataSource.getHikariConfigMXBean();
        poolConfig.setMinimumIdle(0);
        poolConfig.setMaximumPoolSize(maximum);
        poolConfig.setMinimumIdle(minimum);
        logger.info("主库连接池配置已应用: maximumPoolSize={}, minimumIdle={}", maximum, minimum);
    }

    /** 仅创建当前表结构；已有部署须事先满足当前 schema，不执行自动升级。 */
    private void initializeTables() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    email VARCHAR(100) UNIQUE NOT NULL,
                    auth_user_id BIGINT NULL,
                    vip_expires_at TIMESTAMP NULL DEFAULT NULL,
                    UNIQUE KEY uk_users_auth_user_id (auth_user_id),
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS music (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    title VARCHAR(255) NOT NULL,
                    artist VARCHAR(255) NOT NULL,
                    album VARCHAR(255),
                    duration INT, -- 时长，单位秒
                    file_format VARCHAR(10) DEFAULT 'mp3',
                    max_quality VARCHAR(16) DEFAULT NULL,
                    bitrate_bps INT DEFAULT 0,
                    sample_rate_hz INT DEFAULT 0,
                    bits_per_sample INT DEFAULT 0,
                    channels INT DEFAULT 0,
                    play_count INT DEFAULT 0,
                    language VARCHAR(50) NOT NULL DEFAULT '未知语言',
                    tags VARCHAR(500),
                    title_pinyin VARCHAR(500) DEFAULT NULL,
                    title_pinyin_initials VARCHAR(200) DEFAULT NULL,
                    artist_pinyin VARCHAR(500) DEFAULT NULL,
                    artist_pinyin_initials VARCHAR(200) DEFAULT NULL,
                    album_pinyin VARCHAR(500) DEFAULT NULL,
                    title_word_initials VARCHAR(200) DEFAULT NULL,
                    artist_word_initials VARCHAR(200) DEFAULT NULL,
                    upload_user_id INT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    KEY idx_title_pinyin (title_pinyin(100)),
                    KEY idx_title_pinyin_initials (title_pinyin_initials(50)),
                    KEY idx_artist_pinyin (artist_pinyin(100)),
                    KEY idx_title_word_initials (title_word_initials(50)),
                    KEY idx_artist_word_initials (artist_word_initials(50)),
                    KEY idx_music_created_at (created_at),
                    KEY idx_music_play_count (play_count),
                    KEY idx_music_artist (artist(191)),
                    FOREIGN KEY (upload_user_id) REFERENCES users(id)
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS music_lyrics (
                    music_id INT NOT NULL PRIMARY KEY,
                    content MEDIUMTEXT NOT NULL,
                    plain_text MEDIUMTEXT NULL,
                    content_hash CHAR(64) NOT NULL,
                    format VARCHAR(16) NOT NULL DEFAULT 'lrc',
                    is_placeholder BOOLEAN NOT NULL DEFAULT FALSE,
                    size_bytes INT UNSIGNED NOT NULL DEFAULT 0,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    CONSTRAINT fk_music_lyrics_music
                        FOREIGN KEY (music_id) REFERENCES music(id) ON DELETE CASCADE,
                    KEY idx_music_lyrics_updated_at (updated_at),
                    KEY idx_music_lyrics_placeholder (is_placeholder)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS playlists (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    user_id INT NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    description VARCHAR(500),
                    name_pinyin VARCHAR(500) DEFAULT NULL,
                    name_pinyin_initials VARCHAR(200) DEFAULT NULL,
                    name_word_initials VARCHAR(200) DEFAULT NULL,
                    music_count INT DEFAULT 0,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                    INDEX idx_user_id (user_id),
                    INDEX idx_playlists_created_at (created_at),
                    INDEX idx_playlists_name_pinyin (name_pinyin(100)),
                    INDEX idx_playlists_name_pinyin_initials (name_pinyin_initials(50)),
                    INDEX idx_playlists_name_word_initials (name_word_initials(50))
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS playlist_music (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    playlist_id INT NOT NULL,
                    music_id INT NOT NULL,
                    position INT NOT NULL,
                    added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE KEY unique_playlist_music_position (playlist_id, position),
                    FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE CASCADE,
                    FOREIGN KEY (music_id) REFERENCES music(id) ON DELETE CASCADE,
                    INDEX idx_playlist_position (playlist_id, position),
                    INDEX idx_music_id (music_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            logger.info("主库当前表结构已就绪");
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
