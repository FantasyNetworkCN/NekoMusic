package com.neko.music.handlers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neko.music.Main;
import com.neko.music.util.HttpResourceCache;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * AGPL 来源清单：客户端切换到自定义音乐连接源前，用它确认目标服务公开声明了
 * 对应源码地址与 AGPL 许可。
 */
public class AgplSourceManifestHandler extends HttpServlet {

    public static final String PATH = "/api/music/source";
    /** 兼容标准 well-known 路径；部分反向代理默认不转发点目录。 */
    public static final String WELL_KNOWN_PATH = "/.well-known/nekomusic-source.json";
    public static final String SCHEMA = "nekomusic-agpl-source/v1";
    public static final String PROJECT = "NekoMusic";
    public static final String LICENSE = "AGPL-3.0-only";
    public static final String SOURCE_URL_KEY = "agpl.source_url";
    /** 修改服务端代码的部署必须改成实际运行版本对应的公开源码仓库。 */
    public static final String DEFAULT_SOURCE_URL = "https://github.com/FantasyNetworkCN/NekoMusic";
    public static final String LICENSE_URL = "https://www.gnu.org/licenses/agpl-3.0.html";

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ObjectNode root = Main.getObjectMapper().createObjectNode();
        root.put("success", true);
        ObjectNode data = root.putObject("data");
        data.put("schema", SCHEMA);
        data.put("project", PROJECT);
        data.put("license", LICENSE);
        data.put("sourceUrl", currentSourceUrl());
        data.put("licenseUrl", LICENSE_URL);
        data.put("apiProtocol", 1);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=utf-8");
        response.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_PUBLIC_ONE_MINUTE);
        response.getWriter().write(Main.getObjectMapper().writeValueAsString(root));
    }

    static String currentSourceUrl() {
        String configured = Main.getConfigManager() == null
                ? null
                : Main.getConfigManager().effectiveValue(SOURCE_URL_KEY).orElse(null);
        return resolveSourceUrl(configured);
    }

    static String resolveSourceUrl(String configured) {
        String candidate = configured == null ? "" : configured.trim();
        if (candidate.isEmpty()) {
            return DEFAULT_SOURCE_URL;
        }
        try {
            URI uri = URI.create(candidate);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean loopback = host.equals("localhost")
                    || host.equals("::1")
                    || host.startsWith("127.");
            if (uri.isAbsolute() && !host.isBlank()
                    && uri.getUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && ("https".equalsIgnoreCase(uri.getScheme())
                        || ("http".equalsIgnoreCase(uri.getScheme()) && loopback))) {
                return candidate;
            }
        } catch (IllegalArgumentException ignored) {
            // 配置损坏时回退到出厂源码地址，保证清单仍可被客户端解析。
        }
        return DEFAULT_SOURCE_URL;
    }
}
