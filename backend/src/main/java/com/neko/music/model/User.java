package com.neko.music.model;

import java.sql.Timestamp;

/**
 * 用户。只读数据载体（JDBC 行映射后即使用，不再修改）。
 *
 * <p>昵称来自 NekoAuth，仅在运行时使用；本地资料只保存业务字段。
 */
public record User(
        int id,
        String nickname,
        String email,
        String createdAt,
        Timestamp vipExpiresAt) {
}
