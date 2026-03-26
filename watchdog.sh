#!/bin/bash
# ============================================================
# 股票交易服务守护进程脚本
# 功能：检测服务是否运行，如果退出则自动重启
# 用法：由 launchd 管理（见 com.stocktrader.trader.plist）
# ============================================================

JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-8.jdk/Contents/Home
JAVA=$JAVA_HOME/bin/java
WORK_DIR=/Users/caisen/IdeaProjects/MyProject
JAR=$WORK_DIR/target/stock-trader-1.0.0-SNAPSHOT-jar-with-dependencies.jar
LOG_DIR=$WORK_DIR/logs
PID_FILE=$WORK_DIR/logs/trader.pid

# 确保日志目录存在
mkdir -p "$LOG_DIR"

# 获取当前时间
now() { date '+%Y-%m-%d %H:%M:%S'; }

# 检查进程是否存活
is_running() {
    if [ -f "$PID_FILE" ]; then
        local pid=$(cat "$PID_FILE")
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            return 0
        fi
    fi
    return 1
}

# 启动服务
start_service() {
    echo "[$(now)] [watchdog] 正在启动交易服务..." >> "$LOG_DIR/watchdog.log"
    cd "$WORK_DIR"
    $JAVA -jar "$JAR" platform 8090 >> "$LOG_DIR/platform.log" 2>&1 &
    local pid=$!
    echo $pid > "$PID_FILE"
    echo "[$(now)] [watchdog] 服务已启动，PID=$pid" >> "$LOG_DIR/watchdog.log"
}

echo "[$(now)] [watchdog] 守护进程启动" >> "$LOG_DIR/watchdog.log"

# 首次启动
if ! is_running; then
    start_service
else
    echo "[$(now)] [watchdog] 检测到服务已在运行（PID=$(cat $PID_FILE)），跳过启动" >> "$LOG_DIR/watchdog.log"
fi

# 守护循环：每60秒检查一次
while true; do
    sleep 60
    if ! is_running; then
        echo "[$(now)] [watchdog] ⚠️ 服务已停止！正在自动重启..." >> "$LOG_DIR/watchdog.log"
        start_service
    fi
done

