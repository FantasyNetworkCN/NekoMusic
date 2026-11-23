package com.neko.music.handlers;

import com.neko.music.config.ConfigManager;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        assertEquals("public, max-age=" + com.neko.music.util.HttpResourceCache.MAX_AGE_ONE_MINUTE
                        + ", must-revalidate",
                headers.get("cache-control"));
        assertTrue(body.toString().contains("\"schema\":\"" + AgplSourceManifestHandler.SCHEMA + "\""));
        assertTrue(body.toString().contains("\"project\":\"" + AgplSourceManifestHandler.PROJECT + "\""));
        assertTrue(body.toString().contains("\"license\":\"" + AgplSourceManifestHandler.LICENSE + "\""));
        assertTrue(body.toString().contains("\"sourceUrl\":\"\""));
    }

    @Test
    void sourceUrlComesFromRuntimeSetting() {
        assertEquals("https://github.com/example/nekomusic",
                AgplSourceManifestHandler.resolveSourceUrl(
                        " https://github.com/example/nekomusic "));
        assertEquals("",
                AgplSourceManifestHandler.resolveSourceUrl(
                        "https://git.example.com/nekomusic/backend"));
        assertEquals("",
                AgplSourceManifestHandler.resolveSourceUrl("not-a-url"));
        assertTrue(AgplSourceManifestHandler.isValidGithubRepositoryUrl(
                "https://github.com/FantasyNetworkCN/NekoMusic"));
        assertFalse(AgplSourceManifestHandler.isValidGithubRepositoryUrl(
                "https://github.com/FantasyNetworkCN/NekoMusic/tree/main"));
        assertFalse(AgplSourceManifestHandler.isValidGithubRepositoryUrl(
                "https://gitlab.com/FantasyNetworkCN/NekoMusic"));
    }

    @Test
    void exactManifestRouteWinsOverDefaultServletMapping() throws Exception {
        Server server = new Server(0);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void doGet(jakarta.servlet.http.HttpServletRequest request,
                                 jakarta.servlet.http.HttpServletResponse response)
                    throws java.io.IOException {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
            }
        }), "/");
        context.addServlet(new ServletHolder(new AgplSourceManifestHandler()),
                AgplSourceManifestHandler.PATH);
        server.setHandler(context);
        try {
            server.start();
            int port = ((org.eclipse.jetty.server.ServerConnector)
                    server.getConnectors()[0]).getLocalPort();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + port + AgplSourceManifestHandler.PATH)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(HttpServletResponse.SC_OK, response.statusCode());
            assertTrue(response.body().contains(AgplSourceManifestHandler.SCHEMA));
        } finally {
            server.stop();
        }
    }

    @Test
    void actualServletRegistrarServesManifest() throws Exception {
        Server server = new Server(0);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        ServletRegistrar.register(context, new ConfigManager());
        server.setHandler(context);
        try {
            server.start();
            int port = ((org.eclipse.jetty.server.ServerConnector)
                    server.getConnectors()[0]).getLocalPort();
            for (String path : new String[] {
                    AgplSourceManifestHandler.PATH,
                    AgplSourceManifestHandler.WELL_KNOWN_PATH
            }) {
                HttpResponse<String> response = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + port + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(HttpServletResponse.SC_OK, response.statusCode());
                assertTrue(response.body().contains(AgplSourceManifestHandler.SCHEMA));
            }
        } finally {
            server.stop();
        }
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
