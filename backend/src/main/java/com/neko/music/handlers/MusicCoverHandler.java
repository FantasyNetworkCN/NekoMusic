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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class MusicCoverHandler extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(MusicCoverHandler.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String pathInfo = request.getPathInfo();
        
        if (pathInfo == null || pathInfo.equals("/")) {
            // 如果路径为空，返回默认图标
            sendDefaultIcon(response);
            return;
        }
        
        // 解析音乐ID (路径格式: /{id})
        String idStr = pathInfo.replace("/", "");
        int musicId;
        
        try {
            musicId = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            response.setContentType("text/plain;charset=utf-8");
            response.getWriter().println("Invalid music ID");
            return;
        }
        
        // 根据音乐ID查找对应的封面路径
        String coverPath = getCoverPathById(musicId);
        
        if (coverPath != null && !coverPath.isEmpty()) {
            // 检查封面文件是否存在
            Path coverFile = Paths.get(coverPath);
            if (Files.exists(coverFile)) {
                // 文件存在，发送封面文件
                sendImageFile(coverFile, response);
                return;
            }
        }
        
        // 如果封面文件不存在或为空，发送默认图标
        sendDefaultIcon(response);
    }
    
    /**
     * 根据音乐ID获取封面路径
     */
    private String getCoverPathById(int musicId) {
        String coverPath = null;
        
        try (Connection conn = Main.getDatabaseManager().getConnection()) {
            String sql = "SELECT cover_path FROM music WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, musicId);
                
                ResultSet rs = stmt.executeQuery();
                
                if (rs.next()) {
                    coverPath = rs.getString("cover_path");
                }
            }
        } catch (Exception e) {
            logger.error("查询音乐封面路径时出错，音乐ID: " + musicId, e);
        }
        
        return coverPath;
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
                return "image/jpeg"; // 默认使用jpeg
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