package com.neko.music.util;

import java.util.Locale;

/**
 * 邮箱是可选的账号资料，不作为 NekoAuth 身份依据。
 *
 * <p>当前 users.email 有非空唯一约束。无邮箱账号按稳定 Auth ID 保存不可投递的唯一标记，
 * 不存手机号；资料展示、昵称降级和邮件发送统一过滤该标记。</p>
 */
public final class UserEmail {
    /** 仅供本地存储及查询过滤使用，不属于邮箱或客户端字段。 */
    public static final String UNBOUND_PREFIX = "!nekoauth:";

    private UserEmail() {
    }

    public static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public static String forStorage(long authUserId, String email) {
        if (authUserId <= 0) {
            throw new IllegalArgumentException("NekoAuth 用户 ID 无效");
        }
        String normalized = normalize(email);
        return normalized.isEmpty() ? UNBOUND_PREFIX + authUserId : normalized;
    }

    /** 客户端及通知只接收真实邮箱；未绑定邮箱统一表示为空字符串。 */
    public static String visible(String stored) {
        return stored == null || stored.startsWith(UNBOUND_PREFIX) ? "" : stored;
    }
}
