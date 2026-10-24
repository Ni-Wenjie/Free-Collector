@echo off
chcp 936 >nul
setlocal EnableExtensions
title 自由采集器 - 一键启动
rem =====================================================
rem 自由采集器（内部技术测试版）一键启动脚本
rem
rem 用法：双击本文件即可（或命令行执行 run-dev.bat）
rem
rem 脚本会依次完成：
rem   1. 检查 Java / Node / Rust 工具链
rem   2. 检查本机 MySQL(3306) 与 Redis(6379)，未启动时尝试自动拉起
rem   3. 优先用现成 jar 启动后端；无 jar 时才要求 Maven 并打包
rem   4. 等待后端健康检查通过
rem   5. 编译并打开 Tauri 桌面窗口
rem
rem 前置条件：
rem   - MySQL 已执行过 db\schema.sql
rem   - 已执行过 npm install（首次会自动执行）
rem
rem 可选环境变量：
rem   FC_NO_PAUSE=1   启动完成后不等待按键（供自动化调用）
rem =====================================================
cd /d "%~dp0"

set "JAR_PATH=%~dp0server\target\free-collector-server.jar"
set "BACKEND_PORT=17893"
set "FRONTEND_PORT=5173"

echo ============================================================
echo    自由采集器（内部技术测试版） 一键启动
echo    项目目录：%~dp0
echo ============================================================
echo.

rem ============ 1. 基础工具检查 ============
where java >nul 2>&1
if errorlevel 1 (
    echo [X] 未检测到 java，请安装 JDK 17+ 并配置 PATH
    goto :fail
)
echo [OK] Java 已就绪

where npm >nul 2>&1
if errorlevel 1 (
    echo [X] 未检测到 npm，请安装 Node.js 18+ 并配置 PATH
    goto :fail
)
echo [OK] Node/npm 已就绪

rem ---------- Rust 工具链（Tauri 桌面壳编译必需） ----------
where cargo >nul 2>&1
if errorlevel 1 (
    if exist "%USERPROFILE%\.cargo\bin\cargo.exe" (
        echo [!] cargo 不在 PATH，已临时补充 %USERPROFILE%\.cargo\bin
        set "PATH=%PATH%;%USERPROFILE%\.cargo\bin"
    ) else (
        echo [X] 未检测到 Rust 工具链（cargo），无法编译 Tauri 桌面窗口
        echo     请安装 Rust：https://rustup.rs 或 winget install Rustlang.Rustup
        goto :fail
    )
)
if exist "%USERPROFILE%\.cargo\bin\rustup.exe" (
    "%USERPROFILE%\.cargo\bin\rustup.exe" show active-toolchain >nul 2>&1
    if errorlevel 1 (
        echo [!] rustup 未安装默认工具链，正在自动安装 stable（首次需几分钟）...
        "%USERPROFILE%\.cargo\bin\rustup.exe" default stable
        if errorlevel 1 (
            echo [X] Rust 工具链自动安装失败，请手动执行：rustup default stable
            goto :fail
        )
    )
)
echo [OK] Rust 工具链已就绪

echo.

rem ============ 2. MySQL ============
call :check_tcp 3306
if not errorlevel 1 goto :mysql_ready
echo [!] MySQL 未监听 3306，尝试启动 Windows 服务 MySQL / MySQL80 ...
net start MySQL >nul 2>&1
if errorlevel 1 net start MySQL80 >nul 2>&1
call :wait_tcp 3306 15
if errorlevel 1 (
    echo [X] MySQL 未启动成功。请打开 services.msc 启动 MySQL 服务，
    echo     并确认已执行 db\schema.sql 建库建表
    goto :fail
)
:mysql_ready
echo [OK] MySQL 3306 已就绪

rem ============ 3. Redis ============
call :check_tcp 6379
if not errorlevel 1 goto :redis_ready

echo [!] Redis 未监听 6379，尝试启动 Windows 服务 Redis ...
net start Redis >nul 2>&1
call :wait_tcp 6379 6
if not errorlevel 1 goto :redis_ready

set "REDIS_EXE="
set "REDIS_CONF="
if exist "%~dp0tools\redis\redis-server.exe" (
    set "REDIS_EXE=%~dp0tools\redis\redis-server.exe"
    set "REDIS_CONF=%~dp0tools\redis\fc-redis.conf"
    goto :redis_start
)
if exist "%TEMP%\fc-redis\redis-server.exe" (
    set "REDIS_EXE=%TEMP%\fc-redis\redis-server.exe"
    goto :redis_start
)
if exist "C:\Program Files\Redis\redis-server.exe" (
    set "REDIS_EXE=C:\Program Files\Redis\redis-server.exe"
    goto :redis_start
)
echo [X] 未找到可启动的 Redis。任务队列依赖 Redis，请先准备以下任一项：
echo     1) Windows 服务 Redis
echo     2) 便携版：tools\redis\redis-server.exe
echo     3) 本机任意位置 redis-server.exe 并加入 PATH
goto :fail

:redis_start
echo [!] 使用便携版 Redis 启动：%REDIS_EXE%
if defined REDIS_CONF (
    start "free-collector-redis" /min "%REDIS_EXE%" "%REDIS_CONF%"
) else (
    start "free-collector-redis" /min "%REDIS_EXE%" --port 6379
)
call :wait_tcp 6379 15
if errorlevel 1 (
    echo [X] Redis 启动失败，请手动执行：redis-server --port 6379
    goto :fail
)
:redis_ready
echo [OK] Redis 6379 已就绪

echo.

rem ============ 4. 前端依赖 ============
if not exist "%~dp0node_modules" (
    echo [..] 首次运行，正在安装前端依赖 npm install（约 1~3 分钟）...
    call npm install
    if errorlevel 1 (
        echo [X] npm install 失败，请检查网络或 npm 源配置
        goto :fail
    )
)
echo [OK] 前端依赖已就绪

echo.

rem ============ 5. 启动后端 ============
call :check_tcp %BACKEND_PORT%
if not errorlevel 1 (
    echo [OK] 后端已在端口 %BACKEND_PORT% 运行，跳过启动
    goto :wait_backend
)

if not exist "%JAR_PATH%" (
    echo [!] 未发现后端 jar，需要先用 Maven 打包 ...
    where mvn >nul 2>&1
    if errorlevel 1 (
        echo [X] 未检测到 mvn（Maven），无法打包后端
        goto :fail
    )
    echo [..] 正在执行 mvn package（首次约 1~2 分钟）...
    pushd "%~dp0server"
    call mvn -B -DskipTests package
    popd
    if errorlevel 1 (
        echo [X] 后端打包失败，请查看上方错误信息
        goto :fail
    )
)

echo [..] 启动 SpringBoot 后端（新窗口：free-collector-server）...
start "free-collector-server" cmd /k "cd /d "%~dp0server" && echo 正在启动后端... && java -jar target\free-collector-server.jar"

:wait_backend
echo [..] 等待后端健康检查通过（最多 90 秒）...
set /a WAITED=0
:wait_loop
powershell -NoProfile -Command "try{Invoke-RestMethod -Uri http://127.0.0.1:17893/api/health -TimeoutSec 2 | Out-Null; exit 0}catch{exit 1}" >nul 2>&1
if not errorlevel 1 goto :backend_ready
set /a WAITED+=3
if %WAITED% geq 90 goto :backend_timeout
<nul set /p "=."
ping -n 4 127.0.0.1 >nul
goto :wait_loop

:backend_ready
echo.
echo [OK] 后端已就绪：http://127.0.0.1:17893/api/health

echo.

rem ============ 6. 启动前端桌面窗口 ============
call :check_tcp %FRONTEND_PORT%
if not errorlevel 1 (
    echo [!] 端口 5173 已被占用，可能是上次未关闭的 Vite 进程
    echo     若桌面窗口启动失败并提示端口被占用，请关闭旧窗口后重试
)
echo [..] 启动 Tauri 桌面窗口（新窗口：free-collector-ui，首次编译较慢）...
start "free-collector-ui" cmd /k "cd /d "%~dp0" && echo npm run tauri dev ... && call npm run tauri dev"

echo.
echo ============================================================
echo    启动指令已下发
echo    后端窗口：free-collector-server（端口 %BACKEND_PORT%）
echo    前端窗口：free-collector-ui（端口 %FRONTEND_PORT%）
echo    请等待桌面窗口出现；关闭本窗口不影响后台运行
echo ============================================================
if not defined FC_NO_PAUSE pause
exit /b 0

:backend_timeout
echo.
echo [X] 90 秒内后端未就绪，请查看 free-collector-server 窗口的错误输出
echo     常见原因：MySQL 未建表、Redis 断开、端口被占用
goto :fail

:fail
echo.
echo 启动中止，请按上方提示处理后重试。
pause
exit /b 1

rem ---------- 子过程：检测端口是否可连接 ----------
:check_tcp
powershell -NoProfile -Command "$c=New-Object Net.Sockets.TcpClient;try{$c.Connect('127.0.0.1',%~1);$c.Close();exit 0}catch{exit 1}" >nul 2>&1
exit /b %errorlevel%

rem ---------- 子过程：等待端口就绪（参数：端口 超时秒数） ----------
:wait_tcp
set /a WT=0
:wait_tcp_loop
call :check_tcp %~1
if not errorlevel 1 exit /b 0
set /a WT+=1
if %WT% geq %~2 exit /b 1
ping -n 2 127.0.0.1 >nul
goto :wait_tcp_loop
