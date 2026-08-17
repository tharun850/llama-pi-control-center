@echo off
title Building Llama and Pi Control Center
cls
echo =========================================================================
echo    Building Unified Llama and Pi Control Center
echo    1. Compiling Angular 21 Zoneless Frontend
echo    2. Compiling Java 21 Backend
echo =========================================================================
echo.

set ROOT_DIR=%~dp0

:: 1. Build Angular
echo [1/2] Building Angular Frontend to dist...
cd /d "%ROOT_DIR%frontend"
call npm run build
if %errorlevel% neq 0 (
    echo [ERROR] Angular build failed.
    pause
    exit /b %errorlevel%
)

:: 2. Compile Java Backend
echo.
echo [2/2] Compiling Java 21 Backend...
cd /d "%ROOT_DIR%backend"
javac BackendServer.java
if %errorlevel% neq 0 (
    echo [ERROR] Java compilation failed.
    pause
    exit /b %errorlevel%
)

echo.
echo =========================================================================
echo    Build Successful!
echo    Run start.bat or double-click Desktop Shortcut to launch.
echo =========================================================================
echo.
timeout /t 3 /nobreak >nul
