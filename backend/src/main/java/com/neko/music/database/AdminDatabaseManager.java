package com.neko.music.database;

import com.neko.music.model.Admin;
import de.mkammerer.argon2.Argon2Factory;
import de.mkammerer.argon2.Argon2;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class AdminDatabaseManager {
    private final DatabaseManager databaseManager;
    private final Argon2 argon2;

    public AdminDatabaseManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        this.argon2 = Argon2Factory.create();
        initializeTable();
    }

    private void initializeTable() {
        // 创建管理员表
        String adminSql = """
            CREATE TABLE IF NOT EXISTS admins (
                id INT AUTO_INCREMENT PRIMARY KEY,
                username VARCHAR(255) UNIQUE NOT NULL,
                password_hash VARCHAR(255) NOT NULL,
                email VARCHAR(255),
                active BOOLEAN DEFAULT TRUE,
                created_at BIGINT NOT NULL,
                last_login_at BIGINT
            )
        """;
        
        // 创建音乐表（用于统计）
        String musicSql = """
            CREATE TABLE IF NOT EXISTS music (
                id INT AUTO_INCREMENT PRIMARY KEY,
                title VARCHAR(255) NOT NULL,
                artist VARCHAR(255),
                album VARCHAR(255),
                duration INT,
                file_path VARCHAR(500),
                upload_user_id INT DEFAULT 0,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            )
        """;
        
        // 创建用户表（用于统计）
        String userSql = """
            CREATE TABLE IF NOT EXISTS users (
                id INT AUTO_INCREMENT PRIMARY KEY,
                username VARCHAR(255) UNIQUE NOT NULL,
                email VARCHAR(255),
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """;
        
        // 创建搜索日志表（用于统计）
        String searchLogSql = """
            CREATE TABLE IF NOT EXISTS search_logs (
                id INT AUTO_INCREMENT PRIMARY KEY,
                query VARCHAR(255) NOT NULL,
                user_id INT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """;
        
        // 创建访问日志表（用于统计）
        String accessLogSql = """
            CREATE TABLE IF NOT EXISTS access_logs (
                id INT AUTO_INCREMENT PRIMARY KEY,
                user_id INT,
                ip_address VARCHAR(45),
                user_agent TEXT,
                page_visited VARCHAR(500),
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """;
        
        // 创建会话表（用于管理员身份验证）
        String sessionSql = """
            CREATE TABLE IF NOT EXISTS admin_sessions (
                id INT AUTO_INCREMENT PRIMARY KEY,
                admin_id INT NOT NULL,
                session_token VARCHAR(255) UNIQUE NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                expires_at TIMESTAMP NOT NULL,
                is_active BOOLEAN DEFAULT TRUE,
                FOREIGN KEY (admin_id) REFERENCES admins(id)
            )
        """;
        
        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            
            // 执行所有表创建语句
            stmt.execute(adminSql);
            stmt.execute(musicSql);
            stmt.execute(userSql);
            stmt.execute(searchLogSql);
            stmt.execute(accessLogSql);
            stmt.execute(sessionSql);
            
            // 检查并更新music表结构（添加missing列）
            updateMusicTableStructure(conn);
            
            System.out.println("管理员相关表初始化完成");
        } catch (SQLException e) {
            System.err.println("表初始化失败: " + e.getMessage());
        }
    }

    public boolean createAdmin(Admin admin) {
        String sql = """
            INSERT INTO admins (username, password_hash, email, active, created_at)
            VALUES (?, ?, ?, ?, ?)
        """;
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, admin.getUsername());
            stmt.setString(2, admin.getPasswordHash());
            stmt.setString(3, admin.getEmail());
            stmt.setBoolean(4, admin.isActive());
            stmt.setLong(5, admin.getCreatedAt());
            
            int rowsAffected = stmt.executeUpdate();
            return rowsAffected > 0;
        } catch (SQLException e) {
            System.err.println("创建管理员失败: " + e.getMessage());
            return false;
        }
    }

    public Optional<Admin> findAdminByUsername(String username) {
        String sql = "SELECT * FROM admins WHERE username = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Admin admin = new Admin();
                    admin.setId(rs.getInt("id"));
                    admin.setUsername(rs.getString("username"));
                    admin.setPasswordHash(rs.getString("password_hash"));
                    admin.setEmail(rs.getString("email"));
                    admin.setActive(rs.getBoolean("active"));
                    admin.setCreatedAt(rs.getLong("created_at"));
                    admin.setLastLoginAt(rs.getLong("last_login_at"));
                    return Optional.of(admin);
                }
            }
        } catch (SQLException e) {
            System.err.println("查找管理员失败: " + e.getMessage());
        }
        return Optional.empty();
    }

    public boolean updateLastLogin(String username) {
        String sql = "UPDATE admins SET last_login_at = ? WHERE username = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setLong(1, System.currentTimeMillis());
            stmt.setString(2, username);
            
            int rowsAffected = stmt.executeUpdate();
            return rowsAffected > 0;
        } catch (SQLException e) {
            System.err.println("更新登录时间失败: " + e.getMessage());
            return false;
        }
    }

    public boolean updateAdminPassword(String username, String newPasswordHash) {
        String sql = "UPDATE admins SET password_hash = ? WHERE username = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, newPasswordHash);
            stmt.setString(2, username);
            
            int rowsAffected = stmt.executeUpdate();
            return rowsAffected > 0;
        } catch (SQLException e) {
            System.err.println("更新密码失败: " + e.getMessage());
            return false;
        }
    }

    public boolean deleteAdmin(String username) {
        String sql = "DELETE FROM admins WHERE username = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, username);
            
            int rowsAffected = stmt.executeUpdate();
            return rowsAffected > 0;
        } catch (SQLException e) {
            System.err.println("删除管理员失败: " + e.getMessage());
            return false;
        }
    }

    public List<Admin> getAllAdmins() {
        List<Admin> admins = new ArrayList<>();
        String sql = "SELECT * FROM admins ORDER BY created_at DESC";
        
        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                Admin admin = new Admin();
                admin.setId(rs.getInt("id"));
                admin.setUsername(rs.getString("username"));
                admin.setPasswordHash(rs.getString("password_hash"));
                admin.setEmail(rs.getString("email"));
                admin.setActive(rs.getBoolean("active"));
                admin.setCreatedAt(rs.getLong("created_at"));
                admin.setLastLoginAt(rs.getLong("last_login_at"));
                admins.add(admin);
            }
        } catch (SQLException e) {
            System.err.println("获取所有管理员失败: " + e.getMessage());
        }
        return admins;
    }
    
    public boolean adminExists(String username) {
        String sql = "SELECT COUNT(*) FROM admins WHERE username = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            System.err.println("检查管理员是否存在失败: " + e.getMessage());
        }
        return false;
    }
    
    /**
     * 创建管理员会话
     * @param adminId 管理员ID
     * @param sessionToken 会话令牌
     * @param expiresAt 过期时间戳 (毫秒)
     * @return 创建是否成功
     */
    public boolean createAdminSession(int adminId, String sessionToken, long expiresAt) {
        String sql = """
            INSERT INTO admin_sessions (admin_id, session_token, expires_at)
            VALUES (?, ?, ?)
        """;
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setInt(1, adminId);
            stmt.setString(2, sessionToken);
            // 创建一个Timestamp对象来处理毫秒时间戳
            stmt.setTimestamp(3, new Timestamp(expiresAt));
            
            int rowsAffected = stmt.executeUpdate();
            return rowsAffected > 0;
        } catch (SQLException e) {
            System.err.println("创建管理员会话失败: " + e.getMessage());
            e.printStackTrace(); // 添加详细错误日志
            return false;
        }
    }
    
    /**
     * 验证管理员会话令牌
     * @param sessionToken 会话令牌
     * @return 会话是否有效
     */
    public boolean validateAdminSession(String sessionToken) {
        String sql = """
            SELECT COUNT(*) FROM admin_sessions 
            WHERE session_token = ? AND is_active = TRUE AND expires_at > NOW()
        """;
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, sessionToken);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            System.err.println("验证管理员会话失败: " + e.getMessage());
        }
        return false;
    }
    
    /**
     * 使管理员会话失效
     * @param sessionToken 会话令牌
     * @return 是否成功
     */
    public boolean invalidateAdminSession(String sessionToken) {
        String sql = "UPDATE admin_sessions SET is_active = FALSE WHERE session_token = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, sessionToken);
            
            int rowsAffected = stmt.executeUpdate();
            return rowsAffected > 0;
        } catch (SQLException e) {
            System.err.println("使管理员会话失效失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 检查并更新music表结构，确保包含所有必需的列
     * @param conn 数据库连接
     */
    private void updateMusicTableStructure(Connection conn) {
        try {
            // 检查upload_user_id列是否存在
            boolean hasUploadUserId = false;
            try (ResultSet rs = conn.getMetaData().getColumns(null, null, "music", "upload_user_id")) {
                hasUploadUserId = rs.next();
            }
            
            // 检查updated_at列是否存在
            boolean hasUpdatedAt = false;
            try (ResultSet rs = conn.getMetaData().getColumns(null, null, "music", "updated_at")) {
                hasUpdatedAt = rs.next();
            }
            
            // 添加缺少的列
            try (Statement stmt = conn.createStatement()) {
                if (!hasUploadUserId) {
                    stmt.execute("ALTER TABLE music ADD COLUMN upload_user_id INT DEFAULT 0");
                    System.out.println("已添加upload_user_id列到music表");
                }
                
                if (!hasUpdatedAt) {
                    stmt.execute("ALTER TABLE music ADD COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
                    System.out.println("已添加updated_at列到music表");
                }
            }
        } catch (SQLException e) {
            System.err.println("更新music表结构失败: " + e.getMessage());
        }
    }
}