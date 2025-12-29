package com.neko.music;

import com.neko.music.config.ConfigManager;
import com.neko.music.database.DatabaseManager;
import com.neko.music.handlers.MusicSearchHandler;
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

    public static void main(String[] args) throws Exception {
        System.out.println("正在启动NekoMusic音乐平台...");
        
        // 初始化配置管理器
        configManager = new ConfigManager();
        configManager.loadConfig();
        
        // 初始化数据库管理器
        databaseManager = new DatabaseManager(configManager);
        databaseManager.init();
        
        // 创建Jetty服务器，使用配置的端口
        server = new Server(configManager.getPort());
        
        // 创建上下文处理器
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);
        
        // 添加CORS过滤器
        addCorsFilter(context);
        
        // 注册搜索音乐API处理器
        ServletHolder holder = new ServletHolder(new MusicSearchHandler());
        context.addServlet(holder, "/api/music/search");
        
        // 启动服务器
        server.start();
        System.out.println("NekoMusic服务器已在端口" + configManager.getPort() + "启动");
        System.out.println("API端点:");
        System.out.println("  POST /api/music/search - 搜索音乐");
        server.join();
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
}
