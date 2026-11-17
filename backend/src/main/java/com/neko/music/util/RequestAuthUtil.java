package com.neko.music.util;

import com.neko.music.Main;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 从请求中提取并校验用户令牌。
 *
 * <p>支持 {@code Authorization: <token>}、{@code Authorization: Bearer <token>}
 * 以及 {@code ?token=<token>}（浏览器 EventSource 无法自定义请求头时使用）。</p>
 */
public final class RequestAuthUtil {

    private RequestAuthUtil() {
    }

    /** 提取原始令牌，缺失时返回 null。 */
    public static String extractToken(HttpServletRequest request) {
        String raw = request.getHeader("Authorization");
        if (raw == null || raw.isBlank()) {
            raw = request.getParameter("token");
        }
        String token = normalizeBearerToken(raw);
        return token.isEmpty() ? null : token;
    }

    /** 去掉可选 Bearer 前缀并 trim；空令牌返回空串。 */
    public static String normalizeBearerToken(String raw) {
        if (raw == null) {
            return "";
        }
        String token = raw.trim();
        if (token.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            token = token.substring("Bearer ".length()).trim();
        } else if (token.equalsIgnoreCase("Bearer")) {
            token = "";
        }
        return token;
    }

    /** 校验令牌并返回用户 ID，未登录或令牌无效时返回 null。 */
    public static Integer authenticate(HttpServletRequest request) {
        String token = extractToken(request);
        if (token == null) {
            return null;
        }
        return Main.getUserAuthService().validateToken(token).orElse(null);
    }
}
