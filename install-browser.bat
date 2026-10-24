@echo off
chcp 65001 >nul
rem =====================================================
rem 自由采集器：安装内置浏览器 Chromium（仅首次需要）
rem 说明：后端首次采集时也会自动安装，此脚本用于手动重装/镜像加速
rem 国内网络可先设置镜像：
rem   set PLAYWRIGHT_DOWNLOAD_HOST=https://npmmirror.com/mirrors/playwright
rem =====================================================
cd /d %~dp0server

echo 正在安装 Playwright 内置 Chromium（约120MB，请保持网络畅通）...
mvn exec:java -Dexec.args="install chromium"

echo.
echo 安装命令执行结束，请查看上方输出确认是否成功。
pause
