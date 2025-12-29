package com.neko.music.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;

public class ConfigManager {
    private String mysqlHost = "localhost";
    private int mysqlPort = 3306;
    private String mysqlDatabase = "nek_music";
    private String mysqlUsername = "root";
    private String mysqlPassword = "";
    
    private ObjectMapper objectMapper = new ObjectMapper();

    public void loadConfig() {
        try {
            // 尝试从resources目录加载config.yml
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream("config.yml");
            if (inputStream != null) {
                JsonNode configNode = objectMapper.readTree(inputStream);
                
                // 读取MySQL配置
                JsonNode mysqlNode = configNode.get("mysql");
                if (mysqlNode != null) {
                    if (mysqlNode.has("host")) mysqlHost = mysqlNode.get("host").asText();
                    if (mysqlNode.has("port")) mysqlPort = mysqlNode.get("port").asInt();
                    if (mysqlNode.has("database")) mysqlDatabase = mysqlNode.get("database").asText();
                    if (mysqlNode.has("username")) mysqlUsername = mysqlNode.get("username").asText();
                    if (mysqlNode.has("password")) mysqlPassword = mysqlNode.get("password").asText();
                }
            }
            
            System.out.println("配置加载完成:");
            System.out.println("  MySQL Host: " + mysqlHost);
            System.out.println("  MySQL Port: " + mysqlPort);
            System.out.println("  MySQL Database: " + mysqlDatabase);
            System.out.println("  MySQL Username: " + mysqlUsername);
        } catch (Exception e) {
            System.err.println("加载配置时出错: " + e.getMessage());
            // 使用默认值
        }
    }

    // Getters
    public String getMysqlHost() {
        return mysqlHost;
    }

    public int getMysqlPort() {
        return mysqlPort;
    }

    public String getMysqlDatabase() {
        return mysqlDatabase;
    }

    public String getMysqlUsername() {
        return mysqlUsername;
    }

    public String getMysqlPassword() {
        return mysqlPassword;
    }
}