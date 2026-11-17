package com.neko.music.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neko.music.config.ConfigManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NekoAuthAccountServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void acceptsNumericAndDecimalStringSubject() throws Exception {
        assertEquals(2L, parse("2"));
        assertEquals(2L, parse("\"2\""));
        assertEquals(Long.MAX_VALUE, parse("\"" + Long.MAX_VALUE + "\""));
    }

    @Test
    void rejectsInvalidSubject() throws Exception {
        assertEquals(0L, parse("null"));
        assertEquals(0L, parse("0"));
        assertEquals(0L, parse("-1"));
        assertEquals(0L, parse("\"0\""));
        assertEquals(0L, parse("\"-1\""));
        assertEquals(0L, parse("\"+1\""));
        assertEquals(0L, parse("\" 1\""));
        assertEquals(0L, parse("\"1.0\""));
        assertEquals(0L, parse("true"));
        assertEquals(0L, parse("{}"));
        assertEquals(0L, parse("\"9223372036854775808\""));
    }

    @Test
    void serverCallsPreferConfiguredInternalAuthAddress() {
        NekoAuthAccountService service = new NekoAuthAccountService(
                config("http://127.0.0.1:65534/", "https://auth.example.com"), MAPPER);

        assertEquals("http://127.0.0.1:65534", service.resolveServerBaseUrl());
    }

    @Test
    void serverCallsFallBackToIssuerWhenInternalAddressIsBlank() {
        NekoAuthAccountService service = new NekoAuthAccountService(
                config("", "https://auth.example.com/"), MAPPER);

        assertEquals("https://auth.example.com", service.resolveServerBaseUrl());
    }

    @Test
    void nonLoopbackInternalHttpAddressIsRejected() {
        NekoAuthAccountService service = new NekoAuthAccountService(
                config("http://auth.internal:65534", "https://auth.example.com"), MAPPER);

        assertThrows(NekoAuthAccountService.NekoAuthServiceException.class,
                service::resolveServerBaseUrl);
    }

    private static long parse(String json) throws Exception {
        JsonNode node = MAPPER.readTree(json);
        return NekoAuthAccountService.subjectId(node);
    }

    private static ConfigManager config(String internal, String issuer) {
        return new ConfigManager() {
            @Override
            public String getOAuthInternalBaseUrl() {
                return internal;
            }

            @Override
            public String getOAuthIssuer() {
                return issuer;
            }
        };
    }
}
