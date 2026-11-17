package com.neko.music.service;

import com.neko.music.database.UserAccountStore;
import com.neko.music.model.AuthPrincipal;
import com.neko.music.model.UserAccount;
import com.neko.music.model.UserProfile;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserManagementServiceTest {

    @Test
    void adminRoleMayListUsers() throws Exception {
        FakeStore store = new FakeStore();
        store.users = List.of(new UserAccount(7, "listener", "listener@example.com",
                "2026-01-01", false, null));
        UserManagementService service = new UserManagementService(store);

        List<UserAccount> users = service.listUsers(principal("ADMIN"));

        assertEquals(1, users.size());
        assertEquals(7, users.get(0).id());
    }

    @Test
    void userRoleCannotListUsers() {
        UserManagementService service = new UserManagementService(new FakeStore());

        UserManagementException error = assertThrows(UserManagementException.class,
                () -> service.listUsers(principal("USER")));

        assertEquals(UserManagementException.Kind.FORBIDDEN, error.kind());
        assertEquals("common.forbidden", error.messageKey());
    }

    @Test
    void missingUserKeepsLegacySelfServiceFailures() {
        UserManagementService service = new UserManagementService(new FakeStore());

        UserManagementException profile = assertThrows(UserManagementException.class,
                () -> service.getUserProfile(99));
        assertEquals(UserManagementException.Kind.NOT_FOUND, profile.kind());
        assertEquals("user.not_exists", profile.messageKey());
    }

    @Test
    void vipParserTreatsShanghaiLocalTimeAsUtcPlusEight() throws Exception {
        Timestamp parsed = UserManagementService.parseVipExpiresAt("2027-01-01T00:00:00");

        assertEquals(Instant.parse("2026-12-31T16:00:00Z"), parsed.toInstant());
        assertEquals(null, UserManagementService.parseVipExpiresAt("  "));
    }

    @Test
    void adminIdentityIsCaseInsensitiveButStillOnlyAdmin() {
        assertTrue(AdminAccessService.isAdminRole("ADMIN"));
        assertTrue(AdminAccessService.isAdminRole("admin"));
        assertFalse(AdminAccessService.isAdminRole("USER"));
        assertFalse(AdminAccessService.isAdminRole("super_admin"));
    }

    private static AuthPrincipal principal(String role) {
        return new AuthPrincipal(7, 7007L, "listener", "listener@example.com", role);
    }

    private static final class FakeStore implements UserAccountStore {
        private List<UserAccount> users = List.of();
        private Timestamp vip;
        private String nickname = "listener";

        @Override
        public List<UserAccount> listUsers() {
            return users;
        }

        @Override
        public Optional<UserAccount> findUser(int id) {
            return users.stream().filter(user -> user.id() == id).findFirst();
        }

        @Override
        public Optional<UserProfile> findUserProfile(int id) {
            if (id != 7) {
                return Optional.empty();
            }
            return Optional.of(new UserProfile(7, nickname, "listener@example.com",
                    "2026-01-01", vip));
        }

        @Override
        public boolean userEmailExists(String email) {
            return false;
        }

        @Override
        public void createUser(String email) {
        }

        @Override
        public boolean updateUserVip(int id, Timestamp vipExpiresAt) {
            vip = vipExpiresAt;
            return id == 7;
        }

        @Override
        public boolean deleteUser(int id) {
            return false;
        }
    }
}
