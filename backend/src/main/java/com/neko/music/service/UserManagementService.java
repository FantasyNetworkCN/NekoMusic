package com.neko.music.service;

import com.neko.music.database.UserAccountStore;
import com.neko.music.model.AuthPrincipal;
import com.neko.music.model.UserAccount;
import com.neko.music.model.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 用户管理的业务边界：校验、角色策略、口令哈希、会话吊销与事务调度都集中在这里。
 */
public final class UserManagementService {

    private static final Logger logger = LoggerFactory.getLogger(UserManagementService.class);
    private final UserAccountStore store;
    private final NicknameResolver nicknameResolver;

    public UserManagementService(UserAccountStore store) {
        this(store, null);
    }

    public UserManagementService(UserAccountStore store, NicknameResolver nicknameResolver) {
        this.store = store;
        this.nicknameResolver = nicknameResolver;
    }

    public List<UserAccount> listUsers(AuthPrincipal actor) throws UserManagementException {
        requireAdmin(actor);
        try {
            List<UserAccount> users = store.listUsers();
            if (nicknameResolver == null || users.isEmpty()) {
                return users;
            }
            Map<Integer, String> nicknames = nicknameResolver.resolveByLocalUserIds(
                    users.stream().map(UserAccount::id).toList(), "");
            return users.stream()
                    .map(user -> withNickname(user, nicknames.get(user.id())))
                    .toList();
        } catch (SQLException e) {
            throw databaseFailure("admin.user.list_failed", e);
        }
    }

    public UserProfile getUserProfile(int userId) throws UserManagementException {
        try {
            UserProfile profile = store.findUserProfile(userId)
                    .orElseThrow(() -> UserManagementException.notFound("user.not_exists"));
            if (nicknameResolver == null) {
                return profile;
            }
            String nickname = nicknameResolver.resolveByLocalUserId(userId, profile.nickname());
            return withNickname(profile, nickname);
        } catch (SQLException e) {
            throw databaseFailure("common.internal_error", e);
        }
    }

    /** 解析前端提交的 ISO-8601 时间；空值表示清除会员。 */
    static Timestamp parseVipExpiresAt(String raw) throws UserManagementException {
        String value = trim(raw);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Timestamp.from(Instant.parse(value));
        } catch (DateTimeException ignored) {
            // 继续尝试带偏移量的 ISO 时间
        }
        try {
            return Timestamp.from(OffsetDateTime.parse(value).toInstant());
        } catch (DateTimeException ignored) {
            // 继续尝试无偏移量的本地日期时间
        }
        try {
            return Timestamp.from(LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                    .toInstant());
        } catch (DateTimeException e) {
            throw UserManagementException.validation("admin.user.invalid_vip");
        }
    }

    private static void requireAdmin(AuthPrincipal actor) throws UserManagementException {
        if (actor == null || !AdminAccessService.isAdminRole(actor.role())) {
            throw UserManagementException.forbidden("common.forbidden");
        }
    }

    private static UserManagementException databaseFailure(String messageKey, SQLException cause) {
        logger.error("用户管理数据库操作失败", cause);
        return UserManagementException.internal(messageKey, cause);
    }

    private static UserAccount withNickname(UserAccount user, String nickname) {
        if (nickname == null || nickname.isBlank() || nickname.equals(user.nickname())) {
            return user;
        }
        return new UserAccount(user.id(), nickname, user.email(), user.registerTime(), user.vip(),
                user.vipExpiresAt(), user.authUserId());
    }

    private static UserProfile withNickname(UserProfile profile, String nickname) {
        if (nickname == null || nickname.isBlank() || nickname.equals(profile.nickname())) {
            return profile;
        }
        return new UserProfile(profile.id(), nickname, profile.email(), profile.createdAt(),
                profile.vipExpiresAt(), profile.authUserId());
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
