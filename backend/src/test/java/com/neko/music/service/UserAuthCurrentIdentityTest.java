package com.neko.music.service;

import com.neko.music.config.ConfigManager;
import com.neko.music.database.DatabaseManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserAuthCurrentIdentityTest {
    @Test
    void emailConflictDoesNotRelinkAnExistingAccount() {
        List<String> queries = new ArrayList<>();
        DatabaseManager database = new DatabaseManager(new ConfigManager()) {
            @Override
            public Connection getConnection() {
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                            if (method.getName().equals("close")) {
                                return null;
                            }
                            if (method.getName().equals("prepareStatement")) {
                                String sql = (String) args[0];
                                queries.add(sql);
                                return statement(sql);
                            }
                            throw new AssertionError(method.getName());
                        });
            }
        };
        UserAuthService service = new UserAuthService(database, null);
        assertThrows(IllegalStateException.class,
                () -> service.findOrLinkNekoAuthUser(123, "existing@example.com", "当前用户"));
        assertTrue(queries.stream().anyMatch(sql -> sql.startsWith("INSERT INTO users")));
        assertTrue(queries.stream().noneMatch(sql -> sql.startsWith("UPDATE users") || sql.contains("WHERE email")));
    }

    private static PreparedStatement statement(String sql) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "setString", "setLong", "close" -> null;
                    case "executeQuery" -> Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                            new Class<?>[]{ResultSet.class}, (rows, read, parameters) -> switch (read.getName()) {
                                case "next" -> false;
                                case "close" -> null;
                                default -> throw new AssertionError(read.getName());
                            });
                    case "executeUpdate" -> throw new SQLIntegrityConstraintViolationException("重复邮箱");
                    default -> throw new AssertionError(sql + ": " + method.getName());
                });
    }
}
