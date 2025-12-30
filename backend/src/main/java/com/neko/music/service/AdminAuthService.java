package com.neko.music.service;

import com.neko.music.database.AdminDatabaseManager;
import com.neko.music.model.Admin;
import de.mkammerer.argon2.Argon2Factory;
import de.mkammerer.argon2.Argon2;

import java.util.Optional;

public class AdminAuthService {
    private final AdminDatabaseManager adminDatabaseManager;
    private final Argon2 argon2;

    public AdminAuthService(AdminDatabaseManager adminDatabaseManager) {
        this.adminDatabaseManager = adminDatabaseManager;
        this.argon2 = Argon2Factory.create();
    }

    /**
     * 验证管理员凭据
     * @param username 用户名
     * @param password 密码
     * @return 如果验证成功返回管理员对象，否则返回Optional.empty()
     */
    public Optional<Admin> authenticate(String username, String password) {
        Optional<Admin> adminOpt = adminDatabaseManager.findAdminByUsername(username);
        
        if (adminOpt.isPresent()) {
            Admin admin = adminOpt.get();
            // 验证密码
            if (argon2.verify(admin.getPasswordHash(), password.toCharArray())) {
                // 更新最后登录时间
                adminDatabaseManager.updateLastLogin(username);
                return Optional.of(admin);
            }
        }
        
        return Optional.empty();
    }

    /**
     * 创建新管理员
     * @param username 用户名
     * @param password 明文密码
     * @param email 邮箱
     * @return 创建是否成功
     */
    public boolean createAdmin(String username, String password, String email) {
        // 检查用户名是否已存在
        if (adminDatabaseManager.adminExists(username)) {
            return false;
        }
        
        // 使用Argon2哈希密码
        String hash = argon2.hash(10, 65536, 1, password.toCharArray());
        Admin admin = new Admin(username, hash);
        admin.setEmail(email);
        
        return adminDatabaseManager.createAdmin(admin);
    }

    /**
     * 创建新管理员（无邮箱）
     * @param username 用户名
     * @param password 明文密码
     * @return 创建是否成功
     */
    public boolean createAdmin(String username, String password) {
        return createAdmin(username, password, null);
    }

    /**
     * 更改管理员密码
     * @param username 用户名
     * @param oldPassword 旧密码
     * @param newPassword 新密码
     * @return 更改是否成功
     */
    public boolean changePassword(String username, String oldPassword, String newPassword) {
        Optional<Admin> adminOpt = adminDatabaseManager.findAdminByUsername(username);
        
        if (adminOpt.isPresent()) {
            Admin admin = adminOpt.get();
            // 验证旧密码
            if (argon2.verify(admin.getPasswordHash(), oldPassword.toCharArray())) {
                // 哈希新密码
                String newHash = argon2.hash(10, 65536, 1, newPassword.toCharArray());
                return adminDatabaseManager.updateAdminPassword(username, newHash);
            }
        }
        
        return false;
    }

    /**
     * 验证密码强度
     * @param password 密码
     * @return 密码是否符合强度要求
     */
    public boolean isPasswordStrong(String password) {
        // 简单的密码强度验证：长度至少8位
        return password != null && password.length() >= 8;
    }
    
    /**
     * 检查管理员是否存在
     * @param username 用户名
     * @return 管理员是否存在
     */
    public boolean adminExists(String username) {
        return adminDatabaseManager.adminExists(username);
    }
    
    /**
     * 验证管理员令牌
     * @param token 令牌
     * @return 令牌是否有效
     */
    public boolean validateAdminToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }
        
        // 验证数据库中的会话
        return adminDatabaseManager.validateAdminSession(token);
    }
    
    /**
     * 创建管理员会话令牌
     * @param admin 管理员对象
     * @return 生成的会话令牌，如果失败则返回null
     */
    public String createAdminSession(Admin admin) {
        // 生成一个随机的会话令牌
        String sessionToken = generateSessionToken();
        
        // 设置过期时间（例如，24小时后过期）
        long expiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000); // 24小时
        
        // 在数据库中创建会话记录
        boolean success = adminDatabaseManager.createAdminSession(admin.getId(), sessionToken, expiresAt);
        
        if (success) {
            return sessionToken;
        }
        
        return null;
    }
    
    /**
     * 生成会话令牌
     * @return 生成的会话令牌
     */
    private String generateSessionToken() {
        // 生成一个随机的令牌（在实际应用中，应该使用更安全的随机生成器）
        return java.util.UUID.randomUUID().toString();
    }
    
    /**
     * 使管理员会话失效
     * @param token 会话令牌
     * @return 是否成功
     */
    public boolean logout(String token) {
        return adminDatabaseManager.invalidateAdminSession(token);
    }
}