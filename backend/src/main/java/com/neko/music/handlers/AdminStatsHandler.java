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
            
            // 获取最近7天的用户注册趋势数据
            String userTrendQuery = "SELECT DATE(created_at) as date, COUNT(*) as count FROM users WHERE DATE(created_at) >= DATE_SUB(CURDATE(), INTERVAL 7 DAY) GROUP BY DATE(created_at) ORDER BY DATE(created_at)";
            try (PreparedStatement stmt = conn.prepareStatement(userTrendQuery);
                 ResultSet rs = stmt.executeQuery()) {
                // 创建一个包含最近7天数据的列表
                Map<String, Integer> userTrendMap = new HashMap<>();
                while (rs.next()) {
                    userTrendMap.put(rs.getString("date"), rs.getInt("count"));
                }
                stats.put("userTrendData", userTrendMap);
            }
            
            // 获取最近7天的音乐添加趋势数据
            String musicTrendQuery = "SELECT DATE(created_at) as date, COUNT(*) as count FROM music WHERE DATE(created_at) >= DATE_SUB(CURDATE(), INTERVAL 7 DAY) GROUP BY DATE(created_at) ORDER BY DATE(created_at)";
            try (PreparedStatement stmt = conn.prepareStatement(musicTrendQuery);
                 ResultSet rs = stmt.executeQuery()) {
                Map<String, Integer> musicTrendMap = new HashMap<>();
                while (rs.next()) {
                    musicTrendMap.put(rs.getString("date"), rs.getInt("count"));
                }
                stats.put("musicTrendData", musicTrendMap);
            }
            
            // 获取最近7天的访问量趋势数据
            String visitTrendQuery = "SELECT DATE(created_at) as date, COUNT(*) as count FROM access_logs WHERE DATE(created_at) >= DATE_SUB(CURDATE(), INTERVAL 7 DAY) GROUP BY DATE(created_at) ORDER BY DATE(created_at)";
            try (PreparedStatement stmt = conn.prepareStatement(visitTrendQuery);
                 ResultSet rs = stmt.executeQuery()) {
                Map<String, Integer> visitTrendMap = new HashMap<>();
                while (rs.next()) {
                    visitTrendMap.put(rs.getString("date"), rs.getInt("count"));
                }
                stats.put("visitTrendData", visitTrendMap);
            }
            
            // 获取最近7天的搜索量趋势数据
            String searchTrendQuery = "SELECT DATE(created_at) as date, COUNT(*) as count FROM search_logs WHERE DATE(created_at) >= DATE_SUB(CURDATE(), INTERVAL 7 DAY) GROUP BY DATE(created_at) ORDER BY DATE(created_at)";
            try (PreparedStatement stmt = conn.prepareStatement(searchTrendQuery);
                 ResultSet rs = stmt.executeQuery()) {
                Map<String, Integer> searchTrendMap = new HashMap<>();
                while (rs.next()) {
                    searchTrendMap.put(rs.getString("date"), rs.getInt("count"));
                }
                stats.put("searchTrendData", searchTrendMap);
            }
        } catch (Exception e) {
            System.err.println("查询统计数据时出错: " + e.getMessage());
            e.printStackTrace();
            // 出错时返回默认值
            stats.put("totalMusic", 0);
            stats.put("totalUsers", 0);
            stats.put("todayVisits", 0);
            stats.put("totalSearches", 0);
            stats.put("userTrendData", new HashMap<>());
            stats.put("musicTrendData", new HashMap<>());
            stats.put("visitTrendData", new HashMap<>());
            stats.put("searchTrendData", new HashMap<>());
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