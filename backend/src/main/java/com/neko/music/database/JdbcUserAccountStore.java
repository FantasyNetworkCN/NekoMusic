package com.neko.music.database;

import com.neko.music.model.UserAccount;
import com.neko.music.model.UserProfile;
import com.neko.music.util.DbTimeUtil;
import com.neko.music.util.VipUtil;
import com.neko.music.util.UserEmail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 用户管理的 JDBC 实现。所有多步变更都在同一事务内完成。
 */
public final class JdbcUserAccountStore implements UserAccountStore {

    private final DatabaseManager databaseManager;

    public JdbcUserAccountStore(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    @Override
    public List<UserAccount> listUsers() throws SQLException {
        String sql = """
                SELECT id, auth_user_id, email, created_at, vip_expires_at
                FROM users ORDER BY created_at DESC, id DESC
                """;
        List<UserAccount> users = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                Timestamp vip = rs.getTimestamp("vip_expires_at");
                boolean hasVip = !rs.wasNull();
                users.add(new UserAccount(
                        rs.getInt("id"),
                        null,
                        UserEmail.visible(rs.getString("email")),
                        rs.getTimestamp("created_at").toString(),
                        hasVip && VipUtil.isVipActiveNow(vip),
                        hasVip ? vip.toInstant().toString() : null,
                        nullableLong(rs, "auth_user_id")));
            }
        }
        return users;
    }

    @Override
    public Optional<UserProfile> findUserProfile(int id) throws SQLException {
        String sql = """
                SELECT id, auth_user_id, email, created_at, vip_expires_at FROM users WHERE id = ?
                """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Timestamp vip = rs.getTimestamp("vip_expires_at");
                boolean hasVip = !rs.wasNull();
                return Optional.of(new UserProfile(
                        rs.getInt("id"),
                        null,
                        UserEmail.visible(rs.getString("email")),
                        DbTimeUtil.formatStoredWallClock(rs.getString("created_at")),
                        hasVip ? vip : null,
                        nullableLong(rs, "auth_user_id")));
            }
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
