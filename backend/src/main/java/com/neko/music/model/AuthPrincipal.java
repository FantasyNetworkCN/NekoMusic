/*
 * NekoMusic
 * Copyright (C) 2026 NekoMusic contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.neko.music.model;

/**
 * 已通过 NekoAuth introspection 校验的当前用户身份。
 *
 * <p>后台不再维护独立管理员账号；{@code role} 以 NekoAuth 返回值为准，
 * 当前只区分普通用户与管理员。</p>
 */
public record AuthPrincipal(
        int userId,
        long authUserId,
        String username,
        String email,
        String role
) {
}
