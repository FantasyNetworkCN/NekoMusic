package com.neko.music.util;

import com.neko.music.Main;
import com.neko.music.model.AuthPrincipal;
import com.neko.music.service.AdminAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/** 统一后台鉴权：NekoAuth access token + ADMIN 角色。 */
public class PermissionHelper {
    private static final Logger logger = LoggerFactory.getLogger(PermissionHelper.class);
    
    public static AdminAccessService.AccessResult authorize(HttpServletRequest request) {
        AdminAccessService service = Main.getAdminAccessService();
        if (service == null) {
            return AdminAccessService.AccessResult.unauthorized();
        }
        return service.authorize(RequestAuthUtil.extractToken(request));
    }

    /** 当前请求对应的管理员身份；无有效令牌或角色不是 ADMIN 时返回 null。 */
    public static AuthPrincipal getAdminFromRequest(HttpServletRequest request) {
        AdminAccessService.AccessResult result = authorize(request);
        return result.allowedAccess() ? result.principal() : null;
    }

    /**
     * 校验请求是否携带有效的管理员令牌。
     *
     * @param request HTTP请求
     * @return 是否已通过 NekoAuth 管理员角色校验
     */
    public static boolean isAdminAuthorized(HttpServletRequest request) {
        return authorize(request).allowedAccess();
    }
    
    /** 校验后台访问；当前没有权限分级，ADMIN 角色即可访问全部后台能力。 */
    public static boolean checkAdmin(HttpServletRequest request, HttpServletResponse response) {
        AdminAccessService.AccessResult result = authorize(request);
        if (result.status() == AdminAccessService.AccessStatus.UNAUTHORIZED) {
            sendUnauthorizedResponse(response);
            return false;
        }
        if (result.status() == AdminAccessService.AccessStatus.FORBIDDEN) {
            sendForbiddenResponse(response);
            return false;
        }
        return true;
    }
    
    /**
     * 发送未授权响应
     * @param response HTTP响应
     */
    private static void sendUnauthorizedResponse(HttpServletResponse response) {
        try {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"message\":\"未授权访问\"}");
        } catch (IOException e) {
            logger.error("发送未授权响应失败", e);
        }
    }
    
    /**
     * 发送禁止访问响应
     * @param response HTTP响应
     */
    private static void sendForbiddenResponse(HttpServletResponse response) {
        try {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"message\":\"权限不足\"}");
        } catch (IOException e) {
            logger.error("发送禁止访问响应失败", e);
        }
    }
}
