package com.neko.music.util;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class UserEmailTest {
    @Test
    void absentEmailHasUniqueStorageValueAndEmptyPublicValue() {
        String first = UserEmail.forStorage(1, null);
        String second = UserEmail.forStorage(2, "");
        assertNotEquals(first, second);
        assertEquals(first, UserEmail.forStorage(1, "  "));
        assertEquals("", UserEmail.visible(first));
        assertEquals("", UserEmail.visible(second));
        assertEquals("", UserEmail.visible(null));
        assertThrows(IllegalArgumentException.class, () -> UserEmail.forStorage(0, null));
    }

    @Test
    void realEmailNormalizationDoesNotDependOnDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("listener@example.com", UserEmail.forStorage(1, " LISTENER@EXAMPLE.COM "));
            assertEquals("listener@example.com", UserEmail.visible("listener@example.com"));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
