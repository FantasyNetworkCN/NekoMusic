package com.neko.music;

import com.neko.music.config.ConfigManager;
import com.neko.music.database.AdminDatabaseManager;
import com.neko.music.database.DatabaseManager;
import com.neko.music.handlers.MusicSearchHandler;
import com.neko.music.handlers.MusicManagementHandler;
import com.neko.music.handlers.AdminLoginHandler;
import com.neko.music.handlers.AdminStatsHandler;
import com.neko.music.handlers.ChartDataHandler;
import com.neko.music.service.AdminAuthService;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.DispatcherType;
import java.util.EnumSet;

public class Main {
    static {
        // 在类加载时尽早设置字符编码
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.jnu.encoding", "UTF-8");
        System.setProperty("sun.stdout.encoding", "UTF-8");
        System.setProperty("sun.stderr.encoding", "UTF-8");
    }
    
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    
    private static Server server;
    private static DatabaseManager databaseManager;
    private static ConfigManager configManager;
    private static AdminDatabaseManager adminDatabaseManager;
    private static AdminAuthService adminAuthService;

    public static void main(String[] args) throws Exception {
        // 重新设置System.out和System.err的编码
        System.setOut(new java.io.PrintStream(System.out, true, "UTF-8"));
        System.setErr(new java.io.PrintStream(System.err, true, "UTF-8"));
        
        logger.info("正在启动NekoMusic音乐平台...");
        
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
        
        // 注册音乐管理API处理器 - 使用单一路径处理所有音乐管理请求
        ServletHolder musicManagementHolder = new ServletHolder(new MusicManagementHandler());
        context.addServlet(musicManagementHolder, "/api/music/*");         // 处理所有音乐管理请求
        
        // 注册管理员登录API处理器
        ServletHolder adminLoginHolder = new ServletHolder(new AdminLoginHandler());
        context.addServlet(adminLoginHolder, "/api/admin/login");
        
        // 注册管理员统计API处理器
        ServletHolder adminStatsHolder = new ServletHolder(new AdminStatsHandler());
        context.addServlet(adminStatsHolder, "/api/admin/stats");
        
        // 注册图表数据API处理器
        ServletHolder chartDataHolder = new ServletHolder(new ChartDataHandler());
        context.addServlet(chartDataHolder, "/api/admin/chart-data");
        
        // 注册文件上传API处理器
        ServletHolder fileUploadHolder = new ServletHolder(new FileUploadHandler());
        context.addServlet(fileUploadHolder, "/api/music/upload");
        
        // 启动服务器
        server.start();
        logger.info("NekoMusic服务器已在端口{}启动", configManager.getPort());
        logger.info("API端点:");
        logger.info("  POST /api/music/search - 搜索音乐");
        logger.info("  GET /api/music/list - 获取音乐列表 (需要管理员登录)");
        logger.info("  GET /api/music/{id} - 获取特定音乐 (需要管理员登录)");
        logger.info("  POST /api/music/add - 添加音乐 (需要管理员登录)");
        logger.info("  PUT /api/music/edit - 编辑音乐 (需要管理员登录)");
        logger.info("  DELETE /api/music/delete/{id} - 删除音乐 (需要管理员登录)");
        logger.info("  POST /api/admin/login - 管理员登录");
        logger.info("  GET /api/admin/stats - 管理员统计信息 (需要管理员登录)");
        logger.info("  GET /api/admin/chart-data - 管理员图表数据 (需要管理员登录)");
        server.join();
    }
    
    private static void createDefaultAdminIfNotExists() {
        String defaultUsername = "admin";
        String defaultPassword = "admin";
        
        // 检查管理员表是否为空，仅在首次初始化时创建默认管理员
        if (adminDatabaseManager.getAllAdmins().isEmpty()) {
            boolean created = adminAuthService.createAdmin(defaultUsername, defaultPassword, "admin@nekomusic.com");
            if (created) {
                logger.info("默认管理员账号已创建: {}/{}", defaultUsername, defaultPassword);
            } else {
                logger.error("创建默认管理员账号失败");
            }
        } else {
            logger.info("管理员表中已有数据，跳过创建默认管理员账号");
        }
    }
    
    private static void addCorsFilter(ServletContextHandler context) {
        org.eclipse.jetty.servlet.FilterHolder cors = context.addFilter(CrossOriginFilter.class, "/*", EnumSet.allOf(DispatcherType.class));
        cors.setInitParameter(CrossOriginFilter.ALLOWED_ORIGINS_PARAM, "*");
        cors.setInitParameter(CrossOriginFilter.ACCESS_CONTROL_ALLOW_ORIGIN_HEADER, "*");
        cors.setInitParameter(CrossOriginFilter.ALLOWED_METHODS_PARAM, "GET,POST,PUT,DELETE,HEAD,OPTIONS");
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
