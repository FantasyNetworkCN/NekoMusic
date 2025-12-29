package com.neko.music;

import com.neko.music.config.ConfigManager;
import com.neko.music.database.AdminDatabaseManager;
import com.neko.music.database.DatabaseManager;
import com.neko.music.handlers.MusicSearchHandler;
import com.neko.music.handlers.AdminLoginHandler;
import com.neko.music.handlers.AdminStatsHandler;
import com.neko.music.handlers.ChartDataHandler;
import com.neko.music.service.AdminAuthService;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.CrossOriginFilter;

import jakarta.servlet.DispatcherType;
import java.util.EnumSet;

public class Main {
    private static Server server;
    private static DatabaseManager databaseManager;
    private static ConfigManager configManager;
    private static AdminDatabaseManager adminDatabaseManager;
    private static AdminAuthService adminAuthService;

    public static void main(String[] args) throws Exception {
        System.out.println("正在启动NekoMusic音乐平台...");
        
        // 初始化配置管理器
        configManager = new ConfigManager();
        configManager.loadConfig();
        
        // 初始化数据库管理器
        databaseManager = new DatabaseManager(configManager);
        databaseManager.init();
        
        // 初始化管理员数据库管理器和认证服务
        adminDatabaseManager = new AdminDatabaseManager(databaseManager);
        adminAuthService = new AdminAuthService(adminDatabaseManager);
        
        // 创建默认管理员账号（如果不存在）
        createDefaultAdminIfNotExists();
        
        // 创建Jetty服务器，使用配置的端口
        server = new Server(configManager.getPort());
        
        // 创建上下文处理器
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);
        
        // 添加CORS过滤器
        addCorsFilter(context);
        
        // 注册搜索音乐API处理器
        ServletHolder searchHolder = new ServletHolder(new MusicSearchHandler());
        context.addServlet(searchHolder, "/api/music/search");
        
        // 注册管理员登录API处理器
        ServletHolder adminLoginHolder = new ServletHolder(new AdminLoginHandler());
        context.addServlet(adminLoginHolder, "/api/admin/login");
        
        // 注册管理员统计API处理器
        ServletHolder adminStatsHolder = new ServletHolder(new AdminStatsHandler());
        context.addServlet(adminStatsHolder, "/api/admin/stats");
        
        // 注册图表数据API处理器
        ServletHolder chartDataHolder = new ServletHolder(new ChartDataHandler());
        context.addServlet(chartDataHolder, "/api/admin/chart-data");
        
        // 启动服务器
        server.start();
        System.out.println("NekoMusic服务器已在端口" + configManager.getPort() + "启动");
        System.out.println("API端点:");
        System.out.println("  POST /api/music/search - 搜索音乐");
        System.out.println("  POST /api/admin/login - 管理员登录");
        System.out.println("  GET /api/admin/stats - 管理员统计信息 (需要管理员登录)");
        System.out.println("  GET /api/admin/chart-data - 管理员图表数据 (需要管理员登录)");
        server.join();
    }
    
    private static void createDefaultAdminIfNotExists() {
        String defaultUsername = "admin";
        String defaultPassword = "admin";
        
        // 检查管理员表是否为空，仅在首次初始化时创建默认管理员
        if (adminDatabaseManager.getAllAdmins().isEmpty()) {
            boolean created = adminAuthService.createAdmin(defaultUsername, defaultPassword, "admin@nekomusic.com");
            if (created) {
                System.out.println("默认管理员账号已创建: " + defaultUsername + "/" + defaultPassword);
            } else {
                System.err.println("创建默认管理员账号失败");
            }
        } else {
            System.out.println("管理员表中已有数据，跳过创建默认管理员账号");
        }
    }
    
    private static void addCorsFilter(ServletContextHandler context) {
        org.eclipse.jetty.servlet.FilterHolder cors = context.addFilter(CrossOriginFilter.class, "/*", EnumSet.allOf(DispatcherType.class));
        cors.setInitParameter(CrossOriginFilter.ALLOWED_ORIGINS_PARAM, "*");
        cors.setInitParameter(CrossOriginFilter.ACCESS_CONTROL_ALLOW_ORIGIN_HEADER, "*");
        cors.setInitParameter(CrossOriginFilter.ALLOWED_METHODS_PARAM, "GET,POST,HEAD");
        cors.setInitParameter(CrossOriginFilter.ALLOWED_HEADERS_PARAM, "X-Requested-With,Content-Type,Accept,Origin,Authorization");
    }
    
    public static DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
    
    public static ConfigManager getConfigManager() {
        return configManager;
    }
    
    public static AdminDatabaseManager getAdminDatabaseManager() {
        return adminDatabaseManager;
    }
    
    public static AdminAuthService getAdminAuthService() {
        return adminAuthService;
    }
}
