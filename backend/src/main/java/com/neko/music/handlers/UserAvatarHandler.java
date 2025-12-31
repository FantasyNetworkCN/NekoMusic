package com.neko.music.handlers;

import com.neko.music.Main;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class UserAvatarHandler extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(UserAvatarHandler.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String pathInfo = request.getPathInfo();
        
        if (pathInfo == null || pathInfo.equals("/") || pathInfo.equals("/default")) {
            // 如果路径为空或请求默认头像，返回默认图标
            sendDefaultIcon(response);
            return;
        }
        
        // 解析用户ID (路径格式: /{userId} 或 /{userId}/avatar)
        String[] pathParts = pathInfo.split("/");
        String userIdStr = pathParts.length > 1 ? pathParts[1] : null;
        
        if (userIdStr == null || userIdStr.isEmpty()) {
            sendDefaultIcon(response);
            return;
        }
        
        // 尝试解析用户ID
        int userId;
        try {
            userId = Integer.parseInt(userIdStr);
        } catch (NumberFormatException e) {
            logger.warn("无效的用户ID: " + userIdStr);
            sendDefaultIcon(response);
            return;
        }
        
        // 目前我们还没有用户头像上传功能，先返回默认头像
        // 在实际应用中，这里会根据用户ID查找其上传的头像文件
        sendDefaultIcon(response);
    }
    
    /**
     * 发送默认图标
     */
    private void sendDefaultIcon(HttpServletResponse response) throws IOException {
        try {
            // 尝试从类路径加载默认图标
            InputStream defaultIconStream = getClass().getClassLoader().getResourceAsStream("DefaultIcon.png");
            if (defaultIconStream != null) {
                response.setContentType("image/png");
                response.setStatus(HttpStatus.OK_200);
                
                try (InputStream inputStream = defaultIconStream;
                     OutputStream outputStream = response.getOutputStream()) {
                    
                    byte[] buffer = new byte[8192]; // 8KB buffer
                    int bytesRead;
                    
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                    
                    outputStream.flush();
                }
            } else {
                // 如果类路径也找不到，返回404
                response.setStatus(HttpStatus.NOT_FOUND_404);
                response.setContentType("text/plain;charset=utf-8");
                response.getWriter().println("Default icon not found");
            }
        } catch (Exception e) {
            logger.error("发送默认图标时出错", e);
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
            response.setContentType("text/plain;charset=utf-8");
            response.getWriter().println("Error loading default icon");
        }
    }
    
    /**
     * 发送图片文件
     */
    private void sendImageFile(Path imagePath, HttpServletResponse response) throws IOException {
        String mimeType = getMimeType(imagePath.toString());
        response.setContentType(mimeType);
        response.setStatus(HttpStatus.OK_200);
        
        try (InputStream inputStream = Files.newInputStream(imagePath);
             OutputStream outputStream = response.getOutputStream()) {
            
            byte[] buffer = new byte[8192]; // 8KB buffer
            int bytesRead;
            
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            
            outputStream.flush();
        }
    }
    
    /**
     * 根据文件扩展名获取MIME类型
     */
    private String getMimeType(String filePath) {
        String extension = getFileExtension(filePath).toLowerCase();
        
        switch (extension) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "bmp":
                return "image/bmp";
            case "webp":
                return "image/webp";
            default:
                return "image/png"; // 默认使用png
        }
    }
    
    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            return fileName.substring(lastDotIndex + 1);
        }
        return "";
    }
}