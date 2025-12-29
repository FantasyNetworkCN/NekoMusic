package com.neko.music.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neko.music.Main;
import org.eclipse.jetty.http.HttpStatus;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class MusicSearchHandler extends HttpServlet {
    private ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 读取请求体
        StringBuilder requestBody = new StringBuilder();
        try (InputStream inputStream = request.getInputStream()) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                requestBody.append(new String(buffer, 0, bytesRead));
            }
        }

        try {
            // 解析JSON请求体
            SearchRequest searchRequest = objectMapper.readValue(requestBody.toString(), SearchRequest.class);
            
            // 搜索音乐
            List<Music> results = searchMusic(searchRequest.getQuery());
            
            // 返回结果
            SearchResponse searchResponse = new SearchResponse(true, "搜索成功", results);
            
            response.setStatus(HttpStatus.OK_200);
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().println(objectMapper.writeValueAsString(searchResponse));
            
        } catch (Exception e) {
            // JSON解析错误或其他异常
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            response.setContentType("application/json;charset=utf-8");
            ErrorResponse errorResponse = new ErrorResponse("请求格式错误: " + e.getMessage());
            response.getWriter().println(objectMapper.writeValueAsString(errorResponse));
        }
    }
    
    private List<Music> searchMusic(String query) {
        List<Music> results = new ArrayList<>();
        
        try (Connection conn = Main.getDatabaseManager().getConnection()) {
            String sql = "SELECT id, title, artist, album, duration, file_path, upload_user_id, created_at " +
                        "FROM music WHERE title LIKE ? OR artist LIKE ? OR album LIKE ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                String likeQuery = "%" + query + "%";
                stmt.setString(1, likeQuery);
                stmt.setString(2, likeQuery);
                stmt.setString(3, likeQuery);
                
                ResultSet rs = stmt.executeQuery();
                
                while (rs.next()) {
                    Music music = new Music();
                    music.setId(rs.getInt("id"));
                    music.setTitle(rs.getString("title"));
                    music.setArtist(rs.getString("artist"));
                    music.setAlbum(rs.getString("album"));
                    music.setDuration(rs.getInt("duration"));
                    music.setFilePath(rs.getString("file_path"));
                    music.setUploadUserId(rs.getInt("upload_user_id"));
                    music.setCreatedAt(rs.getTimestamp("created_at").toString());
                    
                    results.add(music);
                }
            }
        } catch (Exception e) {
            System.err.println("搜索音乐时出错: " + e.getMessage());
        }
        
        return results;
    }
    
    // 内部类用于表示搜索请求
    private static class SearchRequest {
        private String query;
        
        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
    }
    
    // 内部类用于表示音乐对象
    private static class Music {
        private int id;
        private String title;
        private String artist;
        private String album;
        private int duration; // 时长，单位秒
        private String filePath;
        private int uploadUserId;
        private String createdAt;
        
        // Getters and Setters
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getArtist() { return artist; }
        public void setArtist(String artist) { this.artist = artist; }
        public String getAlbum() { return album; }
        public void setAlbum(String album) { this.album = album; }
        public int getDuration() { return duration; }
        public void setDuration(int duration) { this.duration = duration; }
        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }
        public int getUploadUserId() { return uploadUserId; }
        public void setUploadUserId(int uploadUserId) { this.uploadUserId = uploadUserId; }
        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    }
    
    // 内部类用于表示搜索响应
    private static class SearchResponse {
        private boolean success;
        private String message;
        private List<Music> results;
        
        public SearchResponse(boolean success, String message, List<Music> results) {
            this.success = success;
            this.message = message;
            this.results = results;
        }
        
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public List<Music> getResults() { return results; }
        public void setResults(List<Music> results) { this.results = results; }
    }
    
    // 内部类用于表示错误响应
    private static class ErrorResponse {
        private String error;
        
        public ErrorResponse(String error) {
            this.error = error;
        }
        
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }
}