package com.neko.music.service;

import com.neko.music.config.ConfigManager;
import com.neko.music.database.DatabaseManager;
import com.neko.music.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.Optional;

public class UserAuthService {
    private static final Logger logger = LoggerFactory.getLogger(UserAuthService.class);
    private final DatabaseManager databaseManager;
    private final ConfigManager configManager;
    private final EmailService emailService;
    private final RedisService redisService;
    
    private static final String VERIFICATION_CODE_PREFIX = "verification_code:";
    private static final int VERIFICATION_CODE_EXPIRY = 300; // 5分钟过期

    public UserAuthService(DatabaseManager databaseManager, ConfigManager configManager, EmailService emailService, RedisService redisService) {
        this.databaseManager = databaseManager;
        this.configManager = configManager;
        this.emailService = emailService;
        this.redisService = redisService;
    }

    /**
     * 用户注册
     */
    public boolean registerUser(String username, String password, String email) {
        logger.info("开始注册用户: {}", username);
        
        // 检查用户是否已存在
        if (userExists(username, email)) {
            logger.warn("用户已存在: {}", username);
            return false;
        }

        String hashedPassword = hashPassword(password);
        
        String sql = "INSERT INTO users (username, password, email, created_at) VALUES (?, ?, ?, NOW())";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, username);
            stmt.setString(2, hashedPassword);
            stmt.setString(3, email);
            
            int affectedRows = stmt.executeUpdate();
            logger.info("用户注册结果: {}, 用户名: {}", affectedRows > 0, username);
            return affectedRows > 0;
        } catch (SQLException e) {
            logger.error("用户注册失败: {}", e.getMessage(), e);
        }
        
        return false;
    }

    /**
     * 用户登录
     */
    public Optional<User> authenticate(String usernameOrEmail, String password) {
        logger.info("用户登录尝试: {}", usernameOrEmail);
        
        String hashedPassword = hashPassword(password);
        String sql = "SELECT id, username, password, email, created_at FROM users WHERE (username = ? OR email = ?)";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, usernameOrEmail);
            stmt.setString(2, usernameOrEmail);
            
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                String storedPassword = rs.getString("password");
                
                if (hashedPassword.equals(storedPassword)) {
                    User user = new User();
                    user.setId(rs.getInt("id"));
                    user.setUsername(rs.getString("username"));
                    user.setEmail(rs.getString("email"));
                    user.setCreatedAt(rs.getString("created_at"));
                    
                    logger.info("用户登录成功: {}", usernameOrEmail);
                    return Optional.of(user);
                }
            }
        } catch (SQLException e) {
            logger.error("用户认证失败: {}", e.getMessage(), e);
        }
        
        logger.warn("用户登录失败: {}", usernameOrEmail);
        return Optional.empty();
    }

    /**
     * 发送验证码
     */
    public boolean sendVerificationCode(String email, String username) {
        logger.info("发送验证码至: {}", email);
        
        // 检查邮箱是否在黑名单中
        if (isBlacklistedEmail(email)) {
            logger.warn("邮箱在黑名单中: {}", email);
            return false;
        }
        
        // 生成验证码
        String verificationCode = emailService.generateVerificationCode();
        
        // 尝试发送邮件
        boolean emailSent = emailService.sendVerificationCode(email, username, verificationCode);
        
        if (emailSent) {
            // 将验证码存储到Redis中，设置5分钟过期
            String key = VERIFICATION_CODE_PREFIX + email;
            redisService.setWithExpiry(key, verificationCode, VERIFICATION_CODE_EXPIRY);
            logger.info("验证码已生成并存储到Redis: {}", email);
            return true;
        } else {
            logger.error("发送验证码邮件失败: {}", email);
            return false;
        }
    }
    
    /**
     * 检查邮箱是否在黑名单中
     */
    private boolean isBlacklistedEmail(String email) {
        String emailDomain = email.substring(email.lastIndexOf("@") + 1).toLowerCase().trim();
        String[] blacklist = configManager.getEmailBlacklist().split(",");
        
        for (String blacklistedDomain : blacklist) {
            if (emailDomain.equals(blacklistedDomain.trim())) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * 验证验证码
     */
    public boolean verifyCode(String email, String code) {
        String key = VERIFICATION_CODE_PREFIX + email;
        String storedCode = redisService.get(key);
        
        if (storedCode == null) {
            logger.warn("未找到邮箱对应的验证码: {}", email);
            return false;
        }
        
        // 验证码匹配
        if (storedCode.equals(code)) {
            // 验证成功后删除验证码
            redisService.del(key);
            logger.info("验证码验证成功: {}", email);
            return true;
        } else {
            logger.warn("验证码不匹配: {}", email);
            return false;
        }
    }

    /**
     * 检查用户是否存在
     */
    private boolean userExists(String username, String email) {
        String sql = "SELECT COUNT(*) FROM users WHERE username = ? OR email = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, username);
            stmt.setString(2, email);
            
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            logger.error("检查用户存在性失败: {}", e.getMessage(), e);
        }
        
        return false;
    }

    /**
     * 密码哈希
     */
    private String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashedBytes = md.digest(password.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hashedBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            logger.error("密码哈希失败: {}", e.getMessage(), e);
            return null;
        }
    }
}