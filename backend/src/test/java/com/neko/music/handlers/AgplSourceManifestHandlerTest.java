package com.neko.music.handlers;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgplSourceManifestHandlerTest {

    @Test
    void exposesPublicAgplSourceManifest() throws Exception {
        Map<String, String> headers = new HashMap<>();
        StringWriter body = new StringWriter();
        PrintWriter writer = new PrintWriter(body);

        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getWriter" -> {
                            return writer;
                        }
                        case "setHeader" -> {
                            headers.put(((String) args[0]).toLowerCase(Locale.ROOT), (String) args[1]);
                            return null;
                        }
                        case "setCharacterEncoding", "setContentType", "setStatus" -> {
                            return null;
                        }
                        default -> {
                        }
                    }
                    return defaultValue(method.getReturnType());
                });

        new AgplSourceManifestHandler().doGet(null, response);
        writer.flush();

        assertEquals("public, max-age=" + com.neko.music.util.HttpResourceCache.MAX_AGE_HALF_HOUR,
                headers.get("cache-control"));
        assertTrue(body.toString().contains("\"schema\":\"" + AgplSourceManifestHandler.SCHEMA + "\""));
        assertTrue(body.toString().contains("\"project\":\"" + AgplSourceManifestHandler.PROJECT + "\""));
        assertTrue(body.toString().contains("\"license\":\"" + AgplSourceManifestHandler.LICENSE + "\""));
        assertTrue(body.toString().contains("\"sourceUrl\":\"" + AgplSourceManifestHandler.SOURCE_URL + "\""));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return 0;
    }
}
