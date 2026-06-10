@echo off
chcp 65001 >nul
title 反馈分析系统

echo ========================================
echo   用户反馈智能分析系统
echo ========================================

echo [1/2] Docker Compose 启动全部服务...
docker-compose up -d --build

echo [2/2] 等待服务就绪...
echo.
echo   前端: http://localhost
echo   后端: http://localhost:8088
echo   禅道: http://127.0.0.1/zentao
echo.
echo   首次: 打开前端 → 产品管理 → 新增产品
echo.
pause
