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
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
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
        
        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            
            // 执行所有表创建语句
            stmt.execute(adminSql);
            stmt.execute(musicSql);
            stmt.execute(userSql);
            stmt.execute(searchLogSql);
            stmt.execute(accessLogSql);
            
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
}