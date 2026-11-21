package com.neko.music.service;

import com.neko.music.config.ConfigManager;
import com.neko.music.database.DatabaseManager;
import com.neko.music.database.JdbcUserAccountStore;
import com.neko.music.util.DbTimeUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PhoneOnlyAccountLoginTest {
    @TempDir
    Path directory;
    private DatabaseManager database;
    private UserAuthService users;
    private JdbcUserAccountStore store;
    private NicknameResolver nicknames;

    @BeforeEach
    void createCurrentSchema() throws Exception {
        database = new DatabaseManager(new ConfigManager()) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection delegate = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("users.db"));
                // 使用真实非空唯一约束，仅替换 SQLite 不支持的 MySQL 时区表达式。
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                            if (method.getName().equals("prepareStatement")) {
                                args = args.clone();
                                args[0] = ((String) args[0]).replace(DbTimeUtil.SQL_NOW_SHANGHAI, "CURRENT_TIMESTAMP");
                            }
                            try {
                                return method.invoke(delegate, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
            }
        };
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE users (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      email VARCHAR(100) NOT NULL UNIQUE,
                      auth_user_id BIGINT UNIQUE,
                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      vip_expires_at TIMESTAMP)
                    """);
            statement.execute("CREATE TABLE favorites (user_id INTEGER, music_id INTEGER)");
        }
        NekoAuthAccountService auth = new NekoAuthAccountService(null, null) {
            @Override
            public Map<Long, String> lookupNicknames(Collection<Long> ids) {
                return Map.of();
            }

            @Override
            public List<AccountSummary> searchAccounts(String query) {
                return List.of(new AccountSummary(10001, "", "mobile-user", "手机用户"));
            }

            @Override
            public Optional<Introspection> introspect(String token) {
                return Optional.of(new Introspection(10001, "", "手机用户", false, "USER", ""));
            }
        };
        nicknames = new NicknameResolver(database, auth);
        users = new UserAuthService(database, auth, nicknames);
        store = new JdbcUserAccountStore(database);
    }

    @Test
    void twoPhoneOnlyAccountsCanLoginAndRepeatLoginKeepsIdentity() throws Exception {
        var first = users.findOrLinkNekoAuthUser(10001, null, "手机用户");
        var second = users.findOrLinkNekoAuthUser(10002, "", "另一用户");
        assertNotEquals(first.id(), second.id());
        assertEquals(first.id(), users.findOrLinkNekoAuthUser(10001, "", "新昵称").id());
        assertEquals("", first.email());
        assertEquals("手机用户", first.nickname());
        assertEquals("", second.email());
        assertTrue(users.findEmailByUserId(first.id()).isEmpty());
        assertTrue(users.findEmailByUserId(second.id()).isEmpty());
        assertEquals("", users.findUserById(first.id()).orElseThrow().email());
        assertEquals("", store.findUserProfile(first.id()).orElseThrow().email());
        assertTrue(store.listUsers().stream().allMatch(user -> user.email().isEmpty()));
        var identity = users.resolveIdentity("phone-only-token").orElseThrow();
        assertEquals(first.id(), identity.localUserId());
        assertEquals("", identity.email());
    }

    @Test
    void bindingAndUnbindingEmailPreserveLocalIdAndBusinessData() throws Exception {
        var first = users.findOrLinkNekoAuthUser(10001, null, "手机用户");
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO favorites VALUES (" + first.id() + ", 7)");
        }
        var bound = users.findOrLinkNekoAuthUser(10001, " LISTENER@EXAMPLE.COM ", "手机用户");
        assertEquals(first.id(), bound.id());
        assertEquals("listener@example.com", bound.email());
        assertEquals(Optional.of("listener@example.com"), users.findEmailByUserId(first.id()));

        var unbound = users.findOrLinkNekoAuthUser(10001, "", "手机用户");
        assertEquals(first.id(), unbound.id());
        assertEquals("", unbound.email());
        assertTrue(users.findEmailByUserId(first.id()).isEmpty());
        try (var connection = database.getConnection();
             var statement = connection.prepareStatement("SELECT user_id, music_id FROM favorites");
             var rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(first.id(), rows.getInt("user_id"));
            assertEquals(7, rows.getInt("music_id"));
            assertFalse(rows.next());
        }
    }

    @Test
    void searchAndNicknameFallbackNeverExposeStorageMarkers() {
        users.findOrLinkNekoAuthUser(10001, "", "手机用户");
        var result = nicknames.search("mobile-user");
        assertEquals(1, result.size());
        assertEquals("手机用户", result.getFirst().nickname());
        assertEquals("", result.getFirst().email());
        assertEquals("听众", nicknames.resolveByLocalUserId(result.getFirst().id(), "听众"));
        assertEquals("", users.findUserById(result.getFirst().id()).orElseThrow().email());
    }

    @Test
    void localSearchFiltersUnboundAccountsBeforeApplyingTheResultLimit() {
        for (int i = 1; i <= 25; i++) {
            users.findOrLinkNekoAuthUser(i, "", "手机用户");
        }
        var bound = users.findOrLinkNekoAuthUser(100, "nekoauth-listener@example.com", "邮箱用户");
        var fallback = new NicknameResolver(database, null);
        var result = fallback.search("nekoauth");
        assertEquals(1, result.size());
        assertEquals(bound.id(), result.getFirst().id());
        assertEquals("nekoauth-listener@example.com", result.getFirst().email());
    }

    @Test
    void sharedSessionAndIntrospectionMatchTheSiblingAuthPhoneOnlyContract() throws Exception {
        // 对照上级 NekoAuth：共享会话 email 为 null；introspection 不返回未绑定的 email。
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> cookie = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/api/auth/shared-session", exchange -> {
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            byte[] body = """
                    {"id":10001,"email":null,"username":"phone-user","nickname":"手机用户",
                     "role":"USER","enabled":true,"avatarUrl":"/api/avatar/10001"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
            exchange.close();
        });
        server.createContext("/oauth2/introspect", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            byte[] body = """
                    {"active":true,"sub":"10001","username":"phone-user","name":"手机用户",
                     "role":"USER","scope":"openid profile email phone"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
            exchange.close();
        });
        server.start();
        try {
            ConfigManager config = new ConfigManager() {
                @Override
                public String getOAuthInternalBaseUrl() {
                    return "http://127.0.0.1:" + server.getAddress().getPort();
                }

                @Override
                public String getOAuthIssuer() {
                    return "https://auth.example.com";
                }

                @Override
                public String getOAuthClientId() {
                    return "test-nekomusic";
                }

                @Override
                public String getOAuthClientSecret() {
                    return "test-only-secret";
                }
            };
            var client = new NekoAuthAccountService(config, new ObjectMapper());
            var liveUsers = new UserAuthService(database, client, new NicknameResolver(database, null));
            var account = client.resolveSharedSession("test-session-family");
            var webUser = liveUsers.findOrLinkNekoAuthUser(account.id(), account.email(), account.nickname());
            var identity = liveUsers.resolveIdentity("test-phone-token").orElseThrow();
            assertEquals("nekoauth_sso_session=test-session-family", cookie.get());
            assertTrue(authorization.get().startsWith("Basic "));
            assertEquals(webUser.id(), identity.localUserId());
            assertEquals(10001L, identity.authUserId());
            assertEquals("手机用户", identity.username());
            assertEquals("", webUser.email());
            assertEquals("", identity.email());
            assertTrue(liveUsers.findEmailByUserId(webUser.id()).isEmpty());
        } finally {
            server.stop(0);
        }
    }
}
