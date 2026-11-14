package com.neko.music.handlers;

import com.neko.music.Main;
import com.neko.music.service.ListenTogetherService;
import com.neko.music.util.HttpResourceCache;
import com.neko.music.util.RequestAuthUtil;
import com.neko.music.util.SiteUrlResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一起听房间的 HTTP 入口。
 *
 * <p>创建房间不接受任何选项；加入、重连和关闭都围绕随机房间 URL 里的 token 工作。
 * 真正的播放与临时歌单操作在 WebSocket 端点中处理，这里不维护重复状态。</p>
 */
public final class ListenTogetherHandler extends ApiServlet {

    private static final String ROOT = "/api/listen/rooms";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);
        Integer userId = authenticate(req, resp);
        if (userId == null) {
            return;
        }
        String[] parts = pathParts(req);
        if (parts.length != 1) {
            sendErrorResponse(resp, HttpServletResponse.SC_NOT_FOUND, "listen.not_found");
            return;
        }
        try {
            Map<String, Object> snapshot = Main.getListenTogetherService()
                    .currentSnapshot(parts[0], userId);
            respondData(resp, HttpServletResponse.SC_OK, "listen.join.ok", snapshot);
        } catch (ListenTogetherService.ListenTogetherException e) {
            sendListenError(resp, e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);
        Integer userId = authenticate(req, resp);
        if (userId == null) {
            return;
        }

        String path = normalizedPath(req);
        try {
            if (ROOT.equals(path)) {
                String token = Main.getListenTogetherService().createRoom(userId);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("url", roomUrl(req, token));
                respondData(resp, HttpServletResponse.SC_OK, "listen.create.ok", data);
                return;
            }

            String[] parts = pathParts(req);
            if (parts.length == 2 && "join".equals(parts[1])) {
                ListenTogetherService.JoinResult joined =
                        Main.getListenTogetherService().joinRoom(parts[0], userId);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("ticket", joined.ticket());
                data.put("room", joined.snapshot());
                respondData(resp, HttpServletResponse.SC_OK, "listen.join.ok", data);
                return;
            }
            if (parts.length == 2 && "ticket".equals(parts[1])) {
                String ticket = Main.getListenTogetherService().issueTicket(parts[0], userId);
                respondData(resp, HttpServletResponse.SC_OK, "listen.ticket.ok",
                        Map.of("ticket", ticket));
                return;
            }
            sendErrorResponse(resp, HttpServletResponse.SC_NOT_FOUND, "listen.not_found");
        } catch (ListenTogetherService.ListenTogetherException e) {
            sendListenError(resp, e);
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);
        Integer userId = authenticate(req, resp);
        if (userId == null) {
            return;
        }
        String[] parts = pathParts(req);
        try {
            if (parts.length == 1) {
                Main.getListenTogetherService().closeRoom(parts[0], userId);
                sendSuccessResponse(resp, "listen.close.ok");
                return;
            }
            if (parts.length == 3 && "members".equals(parts[1]) && "me".equals(parts[2])) {
                Main.getListenTogetherService().leaveRoom(parts[0], userId);
                sendSuccessResponse(resp, "listen.leave.ok");
                return;
            }
            sendErrorResponse(resp, HttpServletResponse.SC_NOT_FOUND, "listen.not_found");
        } catch (ListenTogetherService.ListenTogetherException e) {
            sendListenError(resp, e);
        }
    }

    private Integer authenticate(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Integer userId = RequestAuthUtil.authenticate(req);
        if (userId == null) {
            sendErrorResponse(resp, HttpServletResponse.SC_UNAUTHORIZED, "auth.login_required");
        }
        return userId;
    }

    private static void sendListenError(
            HttpServletResponse resp,
            ListenTogetherService.ListenTogetherException error
    ) throws IOException {
        sendJson(resp, error.status(), false, error.messageKey());
    }

    private static void respondData(
            HttpServletResponse resp,
            int status,
            String messageKey,
            Object data
    ) throws IOException {
        sendJson(resp, status, true, messageKey, data);
    }

    private static String roomUrl(HttpServletRequest req, String token) {
        String base = SiteUrlResolver.resolvePublicSiteBase(req);
        if (base == null || base.isBlank()) {
            int port = req.getServerPort();
            boolean defaultPort = ("https".equalsIgnoreCase(req.getScheme()) && port == 443)
                    || ("http".equalsIgnoreCase(req.getScheme()) && port == 80);
            base = req.getScheme() + "://" + req.getServerName()
                    + (defaultPort ? "" : ":" + port);
        }
        base = base.replaceAll("/+$", "");
        return base + "/together/" + token;
    }

    private static String normalizedPath(HttpServletRequest req) {
        String servletPath = req.getServletPath() == null ? "" : req.getServletPath();
        String pathInfo = req.getPathInfo();
        String path = servletPath + (pathInfo == null ? "" : pathInfo);
        return path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1)
                : path;
    }

    private static String[] pathParts(HttpServletRequest req) {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.isBlank() || "/".equals(pathInfo)) {
            return new String[0];
        }
        String value = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isBlank()) {
            return new String[0];
        }
        return value.split("/");
    }
}
