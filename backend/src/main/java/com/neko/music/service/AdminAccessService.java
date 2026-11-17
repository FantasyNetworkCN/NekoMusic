/*
 * NekoMusic
 * Copyright (C) 2026 NekoMusic contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.neko.music.service;

import com.neko.music.model.AuthPrincipal;
import com.neko.music.util.RequestScope;

import java.util.Optional;

/**
 * 后台访问控制：完全复用 NekoAuth 用户令牌与角色，不再存在独立管理员会话。
 */
public final class AdminAccessService {

    private static final String REQUEST_CACHE_PREFIX = "authAdminAccess:";

    public enum AccessStatus {
        ALLOWED,
        UNAUTHORIZED,
        FORBIDDEN
    }

    public record AccessResult(AccessStatus status, AuthPrincipal principal) {
        public static AccessResult unauthorized() {
            return new AccessResult(AccessStatus.UNAUTHORIZED, null);
        }

        public static AccessResult forbidden() {
            return new AccessResult(AccessStatus.FORBIDDEN, null);
        }

        public static AccessResult allowed(AuthPrincipal principal) {
            return new AccessResult(AccessStatus.ALLOWED, principal);
        }

        public boolean allowedAccess() {
            return status == AccessStatus.ALLOWED && principal != null;
        }
    }

    private final UserAuthService userAuthService;

    public AdminAccessService(UserAuthService userAuthService) {
        this.userAuthService = userAuthService;
    }

    public AccessResult authorize(String token) {
        if (token == null || token.isBlank() || userAuthService == null) {
            return AccessResult.unauthorized();
        }
        return RequestScope.computeIfAbsent(
                REQUEST_CACHE_PREFIX + token, () -> authorizeUncached(token));
    }

    private AccessResult authorizeUncached(String token) {
        Optional<UserAuthService.AuthenticatedIdentity> resolved =
                userAuthService.resolveIdentity(token);
        if (resolved.isEmpty()) {
            return AccessResult.unauthorized();
        }
        UserAuthService.AuthenticatedIdentity identity = resolved.get();
        if (!isAdminRole(identity.role())) {
            return AccessResult.forbidden();
        }
        return AccessResult.allowed(new AuthPrincipal(
                identity.localUserId(),
                identity.authUserId(),
                identity.username(),
                identity.email(),
                normalizeRole(identity.role())));
    }

    /** NekoAuth 当前角色只有 ADMIN / USER；大小写兼容，其它值一律不是管理员。 */
    public static boolean isAdminRole(String role) {
        return role != null && "ADMIN".equalsIgnoreCase(role.trim());
    }

    private static String normalizeRole(String role) {
        return isAdminRole(role) ? "ADMIN" : "USER";
    }
}
