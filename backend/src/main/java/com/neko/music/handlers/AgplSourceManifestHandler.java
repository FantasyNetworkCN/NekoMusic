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
    /** 默认不代填源码仓库，必须由管理员在后台明确配置。 */
    public static final String DEFAULT_SOURCE_URL = "";
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
        return isValidGithubRepositoryUrl(candidate) ? candidate : "";
    }

    /** 源码地址必须是公开 GitHub 仓库根地址，客户端会主动调用 GitHub API 复核。 */
    public static boolean isValidGithubRepositoryUrl(String raw) {
        try {
            URI uri = URI.create(raw == null ? "" : raw.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            String[] segments = path.replaceFirst("^/+", "").replaceFirst("/+$", "").split("/");
            return uri.isAbsolute()
                    && host.equals("github.com")
                    && uri.getUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && segments.length == 2
                    && !segments[0].isBlank()
                    && !segments[1].isBlank()
                    && !segments[0].equals(".")
                    && !segments[1].equals(".");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
