package com.neko.music.handlers;

import com.neko.music.model.AuthPrincipal;
import com.neko.music.service.AdminAccessService;
import com.neko.music.util.HttpResourceCache;
import com.neko.music.util.PermissionHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class AdminCurrentHandler extends ApiServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);
        AdminAccessService.AccessResult access = PermissionHelper.authorize(request);
        if (access.status() == AdminAccessService.AccessStatus.UNAUTHORIZED) {
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "auth.unauthorized");
            return;
        }
        if (access.status() == AdminAccessService.AccessStatus.FORBIDDEN) {
            sendErrorResponse(response, HttpServletResponse.SC_FORBIDDEN, "common.forbidden");
            return;
        }
        AuthPrincipal principal = access.principal();
        Map<String, Object> admin = new LinkedHashMap<>();
        admin.put("id", principal.userId());
        admin.put("authUserId", principal.authUserId());
        admin.put("username", principal.username());
        admin.put("email", principal.email());
        admin.put("role", principal.role());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("admin", admin);
        sendJson(response, HttpServletResponse.SC_OK, true, "ok", data);
    }
}
