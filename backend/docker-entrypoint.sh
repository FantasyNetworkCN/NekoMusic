#!/bin/sh
set -eu

if [ -n "${NEKO_JAR_PATH:-}" ]; then
    JAR_PATH="$NEKO_JAR_PATH"
elif [ -f /app/backend.jar ]; then
    JAR_PATH=/app/backend.jar
elif [ -f /app/backend-1.0-SNAPSHOT.jar ]; then
    JAR_PATH=/app/backend-1.0-SNAPSHOT.jar
else
    JAR_PATH=/opt/neko/backend.jar
fi

case "$JAR_PATH" in
    /*) ;;
    *) JAR_PATH="/app/$JAR_PATH" ;;
esac

if [ ! -f "$JAR_PATH" ] || [ ! -r "$JAR_PATH" ]; then
    echo "找不到可读取的后端 JAR：$JAR_PATH" >&2
    echo "请检查 NEKO_JAR_PATH，或确认数据目录中存在 backend-1.0-SNAPSHOT.jar" >&2
    exit 1
fi

echo "启动 NekoMusic 后端 JAR：$JAR_PATH"
exec java -server \
    --enable-native-access=ALL-UNNAMED \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=25 \
    -XX:InitialRAMPercentage=25.0 \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+DisableExplicitGC \
    -Dfile.encoding=UTF-8 \
    -Dsun.jnu.encoding=UTF-8 \
    -Djava.awt.headless=true \
    -jar "$JAR_PATH" "$@"
