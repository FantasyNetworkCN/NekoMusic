# NekoMusic

# 用户收藏API文档

## 概述

用户收藏API允许用户收藏、取消收藏音乐，并获取收藏列表。所有API都需要用户登录认证。

## 认证

所有API请求都需要在HTTP头中包含用户token：

```
Authorization: <token>
```

Token在用户登录时生成并返回给客户端。

## API端点

### 1. 获取收藏列表

**请求:**
```
GET /api/user/favorites
Authorization: <token>
```

**响应:**
```json
{
  "success": true,
  "favorites": [
    {
      "id": 1,
      "title": "歌曲标题",
      "artist": "艺术家",
      "album": "专辑",
      "duration": 180,
      "filename": "song.mp3"
    }
  ]
}
```

### 2. 添加收藏

**请求:**
```
POST /api/user/favorites
Authorization: <token>
Content-Type: application/json

{
  "musicId": 1
}
```

**响应:**
```json
{
  "success": true,
  "message": "收藏成功"
}
```

### 3. 删除收藏

**请求:**
```
DELETE /api/user/favorites/{musicId}
Authorization: <token>
```

**响应:**
```json
{
  "success": true,
  "message": "取消收藏成功"
}
```

## 错误响应

### 401 Unauthorized
```json
{
  "success": false,
  "message": "未提供认证令牌"
}
```

或

```json
{
  "success": false,
  "message": "无效的认证令牌"
}
```

### 400 Bad Request
```json
{
  "success": false,
  "message": "收藏失败或已存在"
}
```

### 500 Internal Server Error
```json
{
  "success": false,
  "message": "服务器内部错误"
}
```

## Token管理

### 获取Token
用户登录时会自动生成token并返回：

```
POST /api/user/login
Content-Type: application/json

{
  "username": "用户名",
  "password": "密码"
}
```

**响应:**
```json
{
  "success": true,
  "message": "登录成功",
  "data": {
    "user": {
      "id": 1,
      "username": "用户名",
      "email": "email@example.com",
      "createdAt": "2024-01-01T00:00:00"
    },
    "token": "随机生成的64位十六进制字符串"
  }
}
```

### Token有效期
- Token有效期为30天
- 过期后需要重新登录获取新token

### 存储Token
客户端应该将token存储在浏览器的localStorage或cookie中，并在每次请求时添加到Authorization头中。

## 数据库表结构

### user_favorites表
```sql
CREATE TABLE IF NOT EXISTS user_favorites (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    music_id INT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY unique_user_music (user_id, music_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (music_id) REFERENCES music(id) ON DELETE CASCADE,
    INDEX idx_user_id (user_id),
    INDEX idx_music_id (music_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### user_tokens表
```sql
CREATE TABLE IF NOT EXISTS user_tokens (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    token VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_user_id (user_id),
    INDEX idx_token (token),
    INDEX idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

## 前端集成示例

### 登录并存储Token
```javascript
async function login(username, password) {
  const response = await fetch('http://localhost:8080/api/user/login', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ username, password })
  });
  
  const data = await response.json();
  if (data.success) {
    // 存储token到localStorage
    localStorage.setItem('userToken', data.data.token);
    localStorage.setItem('userInfo', JSON.stringify(data.data.user));
  }
  return data;
}
```

### 获取收藏列表
```javascript
async function getFavorites() {
  const token = localStorage.getItem('userToken');
  const response = await fetch('http://localhost:8080/api/user/favorites', {
    method: 'GET',
    headers: {
      'Authorization': token
    }
  });
  
  return await response.json();
}
```

### 添加收藏
```javascript
async function addFavorite(musicId) {
  const token = localStorage.getItem('userToken');
  const response = await fetch('http://localhost:8080/api/user/favorites', {
    method: 'POST',
    headers: {
      'Authorization': token,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ musicId })
  });
  
  return await response.json();
}
```

### 删除收藏
```javascript
async function removeFavorite(musicId) {
  const token = localStorage.getItem('userToken');
  const response = await fetch(`http://localhost:8080/api/user/favorites/${musicId}`, {
    method: 'DELETE',
    headers: {
      'Authorization': token
    }
  });
  
  return await response.json();
}
```