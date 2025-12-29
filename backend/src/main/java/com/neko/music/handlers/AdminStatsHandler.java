package com.neko.music.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neko.music.Main;
import com.neko.music.model.Admin;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class AdminStatsHandler extends HttpServlet {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");

        // 处理预检请求
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        // 验证管理员登录状态
        String adminToken = request.getHeader("Authorization");
        if (adminToken == null || !isAdminLoggedIn(adminToken)) {
            sendErrorResponse(response, 401, "需要管理员权限");
            return;
        }

        try {
            Map<String, Object> stats = getPlatformStats();
            
            Map<String, Object> successResponse = new HashMap<>();
            successResponse.put("success", true);
            successResponse.put("data", stats);
            
            response.setStatus(HttpServletResponse.SC_OK);
            PrintWriter out = response.getWriter();
            out.print(objectMapper.writeValueAsString(successResponse));
            out.flush();
        } catch (Exception e) {
            System.err.println("获取统计信息错误: " + e.getMessage());
            e.printStackTrace();
            sendErrorResponse(response, 500, "服务器内部错误");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // 也可以通过POST请求获取统计信息（需要管理员登录验证）
        doGet(request, response);
    }

    private boolean isAdminLoggedIn(String token) {
        try {
            // 检查token是否包含有效的管理员信息（从localStorage的adminToken获取的用户名）
            // 从前端的Authorization header中获取用户名并验证
            Optional<Admin> adminOpt = Main.getAdminDatabaseManager().findAdminByUsername(token);
            return adminOpt.isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, Object> getPlatformStats() throws Exception {
        Map<String, Object> stats = new HashMap<>();
        
        try (Connection conn = Main.getDatabaseManager().getConnection()) {
            // 获取总音乐数
            String musicQuery = "SELECT COUNT(*) FROM music"; // 正确的音乐表
            try (PreparedStatement stmt = conn.prepareStatement(musicQuery);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    stats.put("totalMusic", rs.getInt(1));
                } else {
                    stats.put("totalMusic", 0);
                }
            }
            
            // 获取总用户数
            String userQuery = "SELECT COUNT(*) FROM users"; // 正确的用户表
            try (PreparedStatement stmt = conn.prepareStatement(userQuery);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    stats.put("totalUsers", rs.getInt(1));
                } else {
                    stats.put("totalUsers", 0);
                }
            }
            
            // 获取今日访问量
            String visitQuery = "SELECT COUNT(*) FROM access_logs WHERE DATE(created_at) = CURDATE()"; // 正确的访问日志表
            try (PreparedStatement stmt = conn.prepareStatement(visitQuery);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    stats.put("todayVisits", rs.getInt(1));
                } else {
                    stats.put("todayVisits", 0);
                }
            }
            
            // 获取总搜索次数
            String searchQuery = "SELECT COUNT(*) FROM search_logs"; // 正确的搜索日志表
            try (PreparedStatement stmt = conn.prepareStatement(searchQuery);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    stats.put("totalSearches", rs.getInt(1));
                } else {
                    stats.put("totalSearches", 0);
                }
            }
        } catch (Exception e) {
            System.err.println("查询统计数据时出错: " + e.getMessage());
            e.printStackTrace();
            // 出错时返回默认值
            stats.put("totalMusic", 0);
            stats.put("totalUsers", 0);
            stats.put("todayVisits", 0);
            stats.put("totalSearches", 0);
        }
        
        return stats;
    }

    private void sendErrorResponse(HttpServletResponse response, int statusCode, String message) throws IOException {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("success", false);
        errorResponse.put("message", message);

        response.setStatus(statusCode);
        PrintWriter out = response.getWriter();
        out.print(new ObjectMapper().writeValueAsString(errorResponse));
        out.flush();
    }
}