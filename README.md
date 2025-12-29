# NekoMusic 音乐平台

一个前后端分离的音乐平台，后端使用Java和Jetty实现，不使用Spring Boot框架。

## 项目结构

```
NekoMusic/
├── backend/          # 后端服务
│   ├── src/main/java/com/neko/music/
│   │   ├── Main.java                 # 主启动类
│   │   ├── config/                   # 配置管理
│   │   ├── database/                 # 数据库管理
│   │   └── handlers/                 # API处理器
│   ├── src/main/resources/
│   │   └── config.yml                # 配置文件
│   └── pom.xml                       # Maven配置
└── frontend/         # 前端应用
    ├── src/
    ├── public/
    ├── package.json
    └── ...
```

## 后端功能

- 音乐搜索功能
- MySQL数据库支持

## 后端API端点

### 音乐相关

- `POST /api/music/search` - 搜索音乐
  - 请求体: `{"query": "搜索关键词"}`
  - 响应: `{"success": true, "message": "搜索成功", "results": [...]}`

## 后端配置

在 `backend/src/main/resources/config.yml` 中配置数据库连接信息：

```yaml
port: 8080
mysql:
  host: localhost
  port: 3306
  database: nek_music
  username: root
  password: ""
```

## 运行后端

### 使用Maven (推荐)

1. 确保已安装Java 21和Maven
2. 确保MySQL服务器正在运行
3. 创建数据库 `nek_music`
4. 在backend目录中运行 `mvn clean install`
5. 在backend目录中运行 `mvn exec:java -Dexec.mainClass="com.neko.music.Main"`

后端服务器将在配置文件中指定的端口上启动（默认8080）。

### 使用Maven Wrapper (如果系统中没有Maven)

在backend目录中运行:
```
.\mvnw.cmd clean compile
.\mvnw.cmd exec:java -Dexec.mainClass="com.neko.music.Main"
```

### 手动编译 (如果Maven不可用)

在backend目录中创建一个lib目录，并下载以下JAR文件：

1. jetty-server-11.0.24.jar
2. jetty-servlet-11.0.24.jar
3. jakarta.servlet-api-5.0.0.jar
4. jackson-databind-2.15.2.jar
5. jackson-core-2.15.2.jar
6. jackson-annotations-2.15.2.jar
7. mysql-connector-java-8.0.33.jar
8. HikariCP-5.0.1.jar
9. jackson-dataformat-yaml-2.15.2.jar

然后执行以下步骤：

1. 运行 `compile_simple.bat` 进行编译
2. 运行 `start_simple.bat` 启动服务器

或者手动执行：
```
javac -cp "src/main/java;src/main/resources;lib/*" -d target/classes src/main/java/com/neko/music/*.java src/main/java/com/neko/music/config/*.java src/main/java/com/neko/music/database/*.java src/main/java/com/neko/music/handlers/*.java

java -cp "target/classes;src/main/resources;lib/*" com.neko.music.Main
```

## 前端功能

- 顶部搜索框，支持搜索音乐、艺术家或专辑
- 响应式设计，适配移动端

## 前端运行

在frontend目录中执行：

```bash
npm install
npm run dev
```

前端将在端口5173上启动（默认使用Vite）。

## 前后端集成

前端将通过 `http://localhost:8080/api/music/search` 端点调用后端API进行音乐搜索。