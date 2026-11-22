package com.neko.music.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.Statement;

public class DatabaseInitializer {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseInitializer.class);
    
    public static void initializeTables(DatabaseManager databaseManager) {
        logger.info("开始初始化数据库表...");
        
        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement()) {

            // 创建用户收藏表
            String createFavoritesTable = """
                CREATE TABLE IF NOT EXISTS user_favorites (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    user_id INT NOT NULL,
                    music_id INT NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE KEY unique_user_music (user_id, music_id),
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                    FOREIGN KEY (music_id) REFERENCES music(id) ON DELETE CASCADE,
                    INDEX idx_user_id (user_id),
                    INDEX idx_music_id (music_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
            
            executeTableDdl(stmt, createFavoritesTable,
                    "user_favorites 表创建成功", "创建 user_favorites 表失败（可能是表已存在）");

            String createMusicPlayabilityTable = """
                CREATE TABLE IF NOT EXISTS music_playability (
                    music_id INT NOT NULL PRIMARY KEY,
                    playable BOOLEAN NOT NULL DEFAULT TRUE,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    FOREIGN KEY (music_id) REFERENCES music(id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;

            executeTableDdl(stmt, createMusicPlayabilityTable,
                    "music_playability 表创建成功", "创建 music_playability 表失败（可能是表已存在）");
            
            // 创建用户收藏歌单表
            String createFavoritePlaylistsTable = """
                CREATE TABLE IF NOT EXISTS user_favorite_playlists (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    user_id INT NOT NULL,
                    playlist_id INT NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE KEY unique_user_playlist (user_id, playlist_id),
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                    FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE CASCADE,
                    INDEX idx_user_id (user_id),
                    INDEX idx_playlist_id (playlist_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
            
            executeTableDdl(stmt, createFavoritePlaylistsTable,
                    "user_favorite_playlists 表创建成功", "创建 user_favorite_playlists 表失败（可能是表已存在）");

            // 登录用户搜索画像：只保存规范化后的查询词，用于站内个性化歌单推荐。
            // 同一查询在短时间内重复出现只更新时间、不重复累计次数，避免前端并行搜索造成权重虚高。
            String createUserSearchHistoryTable = """
                CREATE TABLE IF NOT EXISTS user_search_history (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id INT NOT NULL,
                    query_text VARCHAR(255) NOT NULL,
                    normalized_query VARCHAR(255) NOT NULL,
                    search_count INT NOT NULL DEFAULT 1,
                    first_searched_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_searched_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE KEY unique_user_search_query (user_id, normalized_query),
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                    INDEX idx_user_search_history_recent (user_id, last_searched_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;

            executeTableDdl(stmt, createUserSearchHistoryTable,
                    "user_search_history 表创建成功", "创建 user_search_history 表失败（可能是表已存在）");
            
            // 创建用户上传审核表
            String createUserUploadsTable = """
                CREATE TABLE IF NOT EXISTS user_uploads (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    user_id INT NOT NULL,
                    title VARCHAR(255) NOT NULL,
                    artist VARCHAR(255) NOT NULL,
                    album VARCHAR(255),
                    language VARCHAR(50) NOT NULL,
                    tags VARCHAR(255),
                    duration INT DEFAULT 0,
                    music_file_path VARCHAR(512) NOT NULL,
                    cover_file_path VARCHAR(512),
                    lyrics_file_path VARCHAR(512),
                    status ENUM('pending', 'approved', 'rejected') DEFAULT 'pending',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                    INDEX idx_user_id (user_id),
                    INDEX idx_status (status),
                    INDEX idx_created_at (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
            
            executeTableDdl(stmt, createUserUploadsTable,
                    "user_uploads 表创建成功", "创建 user_uploads 表失败（可能是表已存在）");

            // 用户歌词报错：保存提交版本与审核状态，审核通过后才覆盖正式歌词。
            String createLyricsReportsTable = """
                CREATE TABLE IF NOT EXISTS lyrics_reports (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    music_id INT NOT NULL,
                    user_id INT NOT NULL,
                    status ENUM('pending', 'approved', 'rejected') NOT NULL DEFAULT 'pending',
                    format VARCHAR(16) NOT NULL DEFAULT 'neko-v1',
                    content MEDIUMTEXT NOT NULL,
                    original_content MEDIUMTEXT NULL,
                    original_format VARCHAR(16) NULL,
                    note VARCHAR(500) NULL,
                    review_note VARCHAR(500) NULL,
                    reviewer_id INT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    reviewed_at DATETIME NULL,
                    FOREIGN KEY (music_id) REFERENCES music(id) ON DELETE CASCADE,
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                    INDEX idx_lyrics_reports_pending (status, created_at),
                    INDEX idx_lyrics_reports_user_music (user_id, music_id, status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
            executeTableDdl(stmt, createLyricsReportsTable,
                    "lyrics_reports 表创建成功", "创建 lyrics_reports 表失败（可能是表已存在）");

            // 创建歌曲评论表（每首歌的评论与楼层回复）
            String createMusicCommentsTable = """
                CREATE TABLE IF NOT EXISTS music_comments (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    music_id INT NOT NULL,
                    user_id INT NOT NULL,
                    parent_id INT NULL,
                    reply_to_user_id INT NULL,
                    content VARCHAR(500) NOT NULL,
                    ip_region VARCHAR(64) NOT NULL DEFAULT '',
                    client_ip VARCHAR(45) NOT NULL DEFAULT '',
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (music_id) REFERENCES music(id) ON DELETE CASCADE,
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                    INDEX idx_music_comments_music (music_id, parent_id, created_at),
                    INDEX idx_music_comments_user (user_id),
                    INDEX idx_music_comments_parent (parent_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;

            executeTableDdl(stmt, createMusicCommentsTable,
                    "music_comments 表创建成功", "创建 music_comments 表失败（可能是表已存在）");
            
            // VIP 价目表（与主库一并备份）
            String createVipPricing = """
                CREATE TABLE IF NOT EXISTS vip_pricing (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    months INT NOT NULL DEFAULT 0,
                    days INT NOT NULL DEFAULT 0,
                    price_yuan DECIMAL(12,2) NOT NULL,
                    sort_order INT NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    KEY idx_vip_pricing_sort (sort_order, id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
            executeTableDdl(stmt, createVipPricing,
                    "vip_pricing 表已就绪", "创建 vip_pricing 表失败（可能已存在）");

            // 系统设置：运行时配置的唯一持久化来源
            String createSystemSettings = """
                CREATE TABLE IF NOT EXISTS system_settings (
                    setting_key VARCHAR(128) NOT NULL,
                    setting_value TEXT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    updated_by INT NULL,
                    PRIMARY KEY (setting_key)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
            executeTableDdl(stmt, createSystemSettings,
                    "system_settings 表已就绪", "创建 system_settings 表失败（可能已存在）");

            // 站内消息（收件箱）：评论回复等消息落库即视为送达，离线用户靠游标补拉
            String createUserNotifications = """
                CREATE TABLE IF NOT EXISTS user_notifications (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    user_id INT NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    title VARCHAR(128) NOT NULL,
                    body VARCHAR(500) NOT NULL DEFAULT '',
                    link VARCHAR(255) NOT NULL DEFAULT '',
                    actor_user_id INT NULL,
                    is_read TINYINT(1) NOT NULL DEFAULT 0,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                    INDEX idx_user_notifications_user (user_id, id),
                    INDEX idx_user_notifications_unread (user_id, is_read)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
            executeTableDdl(stmt, createUserNotifications,
                    "user_notifications 表已就绪", "创建 user_notifications 表失败（可能已存在）");

            String createVipPayOrders = """
                CREATE TABLE IF NOT EXISTS vip_pay_orders (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    out_trade_no VARCHAR(32) NOT NULL,
                    user_id INT NOT NULL,
                    pricing_id INT NULL,
                    months INT NOT NULL DEFAULT 0,
                    days INT NOT NULL DEFAULT 0,
                    money DECIMAL(12,2) NOT NULL,
                    pay_type VARCHAR(16) NOT NULL,
                    status ENUM('pending','paid','cancelled') NOT NULL DEFAULT 'pending',
                    zpay_trade_no VARCHAR(128) NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    paid_at TIMESTAMP NULL DEFAULT NULL,
                    UNIQUE KEY uk_vip_pay_out_trade_no (out_trade_no),
                    KEY idx_vip_pay_user_created (user_id, created_at),
                    CONSTRAINT fk_vip_pay_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
            executeTableDdl(stmt, createVipPayOrders,
                    "vip_pay_orders 表已就绪", "创建 vip_pay_orders 表失败（可能已存在）");

            createAppReleaseTable(stmt);

            logger.info("数据库表初始化完成");
            
        } catch (Exception e) {
            throw new IllegalStateException("初始化当前数据库表失败", e);
        }
    }

    /** 创建当前表结构，失败明确中止初始化。 */
    private static void executeTableDdl(Statement stmt, String ddl, String successMessage, String failureMessage) {
        try {
            stmt.execute(ddl);
            logger.info(successMessage);
        } catch (Exception e) {
            throw new IllegalStateException(failureMessage, e);
        }
    }

    private static void createAppReleaseTable(Statement stmt) throws Exception {
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS app_release (
                android_ver VARCHAR(64) NOT NULL,
                pc_ver VARCHAR(64) NOT NULL,
                pending_android_ver VARCHAR(64) NULL,
                pending_pc_ver VARCHAR(64) NULL,
                pending_effective_at TIMESTAMP NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
    }
}
