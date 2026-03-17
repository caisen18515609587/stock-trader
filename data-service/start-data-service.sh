#!/bin/bash
# 启动股票数据服务
cd /Users/caisen/IdeaProjects/MyProject/data-service
nohup python3 app.py >> data-service.log 2>&1 &
echo "数据服务已启动，PID: $!"
echo "日志: data-service/data-service.log"
echo "测试: curl http://localhost:8099/health"

