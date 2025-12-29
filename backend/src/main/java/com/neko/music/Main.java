package com.neko.music;

import com.neko.music.config.ConfigManager;
import com.neko.music.database.DatabaseManager;
import com.neko.music.handlers.MusicSearchHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

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
        
        // 创建Jetty服务器
        server = new Server(8080);
        
        // 创建上下文处理器
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);
        
        // 注册搜索音乐API处理器
        context.addServlet(new ServletHolder(MusicSearchHandler.class), "/api/music/search");
        
        // 启动服务器
        server.start();
        System.out.println("NekoMusic服务器已在端口8080启动");
        System.out.println("API端点:");
        System.out.println("  POST /api/music/search - 搜索音乐");
        server.join();
    }
    
    public static DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
    
    public static ConfigManager getConfigManager() {
        return configManager;
    }
}
