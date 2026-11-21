package com.neko.music.handlers;

import com.neko.music.service.MusicSeparationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicFileHandlerSeparationTest {

    @Test
    void keepsExistingJsonContractWithoutSeparateParameter() throws Exception {
        AtomicBoolean separationCalled = new AtomicBoolean();
        Response result = request(Map.of(), "quality", "hq",
                dependencies(42, true, separationCalled));

        assertEquals(200, result.status());
        assertEquals("application/json;charset=UTF-8", result.contentType());
        assertTrue(result.body().contains("\"url\":\"/media/music/7/7.mp3\""));
        assertFalse(separationCalled.get());
    }

    @Test
    void separateRequiresLogin() throws Exception {
        Response result = request(Map.of(), "separate", "1",
                dependencies(null, true, new AtomicBoolean()));

        assertEquals(401, result.status());
        assertTrue(result.body().contains("请先登录"));
    }

    @Test
    void separateRequiresActiveVip() throws Exception {
        Response result = request(Map.of(), "separate", "1",
                dependencies(42, false, new AtomicBoolean()));

        assertEquals(403, result.status());
        assertTrue(result.body().contains("VIP"));
    }

    @Test
    void separateReturnsVocalsThenAccompanimentAsTwoTextLines() throws Exception {
        AtomicBoolean separationCalled = new AtomicBoolean();
        Response result = request(Map.of(), "separate", "1",
                dependencies(42, true, separationCalled));

        assertEquals(200, result.status());
        assertEquals("text/plain;charset=UTF-8", result.contentType());
        assertEquals("private, no-store", result.headers().get("cache-control"));
        assertEquals("/media/music/7/vocals-v1.mp3\n/media/music/7/accompaniment-v1.mp3\n",
                result.body());
        assertTrue(separationCalled.get());
    }

    @Test
    void invalidSeparateValueIsRejectedBeforeDoingWork() throws Exception {
        AtomicBoolean separationCalled = new AtomicBoolean();
        Response result = request(Map.of(), "separate", "maybe",
                dependencies(42, true, separationCalled));

        assertEquals(400, result.status());
        assertFalse(separationCalled.get());
    }

    @Test
    void streamSeparateRequiresLoginBeforeOpeningSse() throws Exception {
        Response result = request("/7/stream", Map.of(), "separate", "1",
                dependencies(null, true, new AtomicBoolean()));

        assertEquals(401, result.status());
        assertTrue(result.body().contains("请先登录"));
    }

    private static MusicFileHandler.Dependencies dependencies(Integer userId, boolean vip,
                                                               AtomicBoolean separationCalled) {
        return new MusicFileHandler.Dependencies(
                request -> userId,
                id -> vip,
                id -> true,
                (id, quality) -> "/tmp/7.mp3",
                id -> {
                    separationCalled.set(true);
                    return new MusicSeparationService.Result(
                            Path.of("/tmp/vocals-v1.mp3"),
                            Path.of("/tmp/accompaniment-v1.mp3"));
                },
                id -> {
                    throw new UnsupportedOperationException("stream path not used");
                },
                (id, ip) -> {
                });
    }

    private static Response request(Map<String, String> headers, String parameter, String value,
                                    MusicFileHandler.Dependencies dependencies) throws Exception {
        return request("/7", headers, parameter, value, dependencies);
    }

    private static Response request(String pathInfo, Map<String, String> headers, String parameter,
                                    String value, MusicFileHandler.Dependencies dependencies) throws Exception {
        int[] status = {200};
        String[] contentType = {""};
        Map<String, String> responseHeaders = new HashMap<>();
        StringWriter body = new StringWriter();
        PrintWriter writer = new PrintWriter(body);
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
                MusicFileHandlerSeparationTest.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setStatus" -> {
                        status[0] = (int) args[0];
                        yield null;
                    }
                    case "setHeader" -> {
                        responseHeaders.put(((String) args[0]).toLowerCase(Locale.ROOT), (String) args[1]);
                        yield null;
                    }
                    case "setContentType" -> {
                        contentType[0] = (String) args[0];
                        yield null;
                    }
                    case "getWriter" -> writer;
                    default -> defaultValue(method.getReturnType());
                });

        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                MusicFileHandlerSeparationTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPathInfo" -> pathInfo;
                    case "getHeader" -> headers.get((String) args[0]);
                    case "getParameter" -> parameter.equals(args[0]) ? value : null;
                    case "getRemoteAddr" -> "127.0.0.1";
                    default -> defaultValue(method.getReturnType());
                });

        new MusicFileHandler(dependencies).doGet(request, response);
        writer.flush();
        return new Response(status[0], contentType[0], Map.copyOf(responseHeaders), body.toString());
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
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

    private record Response(int status, String contentType, Map<String, String> headers, String body) {
    }
}
