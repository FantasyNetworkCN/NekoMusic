package com.neko.music.service;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAccessServiceTest {

    @Test
    void nekoAuthAdminRoleGetsAllBackendAccess() {
        AdminAccessService service = new AdminAccessService(new StubUserAuth("ADMIN"));

        AdminAccessService.AccessResult result = service.authorize("token");

        assertTrue(result.allowedAccess());
        assertEquals(AdminAccessService.AccessStatus.ALLOWED, result.status());
        assertNotNull(result.principal());
        assertEquals(7, result.principal().userId());
        assertEquals(7007L, result.principal().authUserId());
        assertEquals("ADMIN", result.principal().role());
    }

    @Test
    void ordinaryUserIsForbiddenNotUnauthorized() {
        AdminAccessService service = new AdminAccessService(new StubUserAuth("USER"));

        AdminAccessService.AccessResult result = service.authorize("token");

        assertEquals(AdminAccessService.AccessStatus.FORBIDDEN, result.status());
        assertFalse(result.allowedAccess());
        assertNull(result.principal());
    }

    @Test
    void invalidOrMissingTokenIsUnauthorized() {
        AdminAccessService service = new AdminAccessService(new StubUserAuth(null));

        assertEquals(AdminAccessService.AccessStatus.UNAUTHORIZED,
                service.authorize("bad-token").status());
        assertEquals(AdminAccessService.AccessStatus.UNAUTHORIZED,
                service.authorize("").status());
        assertEquals(AdminAccessService.AccessStatus.UNAUTHORIZED,
                service.authorize(null).status());
    }

    private static final class StubUserAuth extends UserAuthService {
        private final String role;

        private StubUserAuth(String role) {
            super(null, null);
            this.role = role;
        }

        @Override
        public Optional<AuthenticatedIdentity> resolveIdentity(String token) {
            if (role == null || !"token".equals(token)) {
                return Optional.empty();
            }
            return Optional.of(new AuthenticatedIdentity(
                    7, 7007L, "listener", "listener@example.com", role));
        }
    }
}
