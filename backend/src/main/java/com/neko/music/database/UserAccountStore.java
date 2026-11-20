package com.neko.music.database;

import com.neko.music.model.UserAccount;
import com.neko.music.model.UserProfile;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 用户管理的数据访问边界。Servlet 与业务规则不直接拼 SQL，便于测试和复用。
 */
public interface UserAccountStore {

    List<UserAccount> listUsers() throws SQLException;

    Optional<UserProfile> findUserProfile(int id) throws SQLException;
}
