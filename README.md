# NekoMusic 音乐平台后端

一个基于Java和Jetty的音乐平台后端服务，不使用Spring Boot框架。

## 功能特性

- 用户注册和登录
- 音乐搜索和浏览
- 音乐添加功能
- MySQL数据库支持

## API端点

### 用户相关

- `POST /api/login` - 用户登录
  - 请求体: `{"username": "用户名", "password": "密码"}`
  - 响应: `{"success": true, "message": "登录成功", "token": "令牌"}`

- `POST /api/user/register` - 用户注册
  - 请求体: `{"username": "用户名", "password": "密码", "email": "邮箱"}`
  - 响应: `{"success": true, "message": "注册成功"}`

### 音乐相关

- `POST /api/music/search` - 搜索音乐
  - 请求体: `{"query": "搜索关键词"}`
  - 响应: `{"success": true, "message": "搜索成功", "results": [...]}`

- `POST /api/music/list` - 获取音乐列表
  - 请求体: 无
  - 响应: `{"success": true, "message": "获取列表成功", "musicList": [...]}`

- `POST /api/music/add` - 添加音乐
  - 请求体: `{"title": "标题", "artist": "艺术家", "album": "专辑", "duration": 180, "filePath": "文件路径", "uploadUserId": 1}`
  - 响应: `{"success": true, "message": "添加音乐成功"}`

## 配置

在 `src/main/resources/config.yml` 中配置数据库连接信息：

```yaml
mysql:
  host: localhost
  port: 3306
  database: nek_music
  username: root
  password: ""
```

## 运行方式

### 方式一：使用Maven（推荐）

1. 确保已安装Java 21和Maven
2. 确保MySQL服务器正在运行
3. 创建数据库 `nek_music`
4. 运行 `mvn clean install`
5. 运行 `mvn exec:java -Dexec.mainClass="com.neko.music.NekoMusic"`

服务器将在端口8080上启动。

### 方式二：手动下载依赖项

如果系统中没有Maven，可以手动下载依赖项：

1. 下载以下JAR文件到 `lib/` 目录：
   - jetty-server-11.0.24.jar
   - jetty-servlet-11.0.24.jar
   - jackson-databind-2.15.2.jar
   - jackson-core-2.15.2.jar
   - jackson-annotations-2.15.2.jar
   - mysql-connector-java-8.0.33.jar
   - HikariCP-5.0.1.jar

2. 确保已安装Java 21并设置JAVA_HOME环境变量

3. 确保MySQL服务器正在运行并创建数据库 `nek_music`

4. 运行 `compile.bat` 编译项目

5. 运行 `start.bat` 启动服务器

服务器将在端口8080上启动。