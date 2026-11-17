package com.neko.music.database;

import com.neko.music.model.UserAccount;
import com.neko.music.model.UserProfile;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 用户管理的数据访问边界。Servlet 与业务规则不直接拼 SQL，便于测试和复用。
 */
public interface UserAccountStore {

    List<UserAccount> listUsers() throws SQLException;

    Optional<UserAccount> findUser(int id) throws SQLException;

    Optional<UserProfile> findUserProfile(int id) throws SQLException;

    boolean userEmailExists(String email) throws SQLException;

    default Optional<Long> findUserAuthIdByEmail(String email) throws SQLException {
        return Optional.empty();
    }

    default void bindUserAuthId(String email, long authUserId) throws SQLException {
    }

    void createUser(String email) throws SQLException;

    boolean updateUserVip(int id, Timestamp vipExpiresAt) throws SQLException;

    boolean deleteUser(int id) throws SQLException;
}
