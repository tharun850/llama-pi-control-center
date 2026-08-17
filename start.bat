@echo off
title Llama and Pi Control Center
cls
echo =========================================================================
echo    Llama Server and Pi Control Center
echo    Starting Unified Java 21 Server (Frontend UI + Backend APIs)
echo =========================================================================
echo.

set ROOT_DIR=%~dp0

:: Start Unified Java Server
echo [1/1] Starting Java Server on http://127.0.0.1:8765 ...
start "Llama and Pi Unified Server (Port 8765)" cmd.exe /k "cd /d "%ROOT_DIR%backend" && java BackendServer.java"

timeout /t 1 /nobreak >nul

:: Open browser on port 8765
echo Opening http://127.0.0.1:8765 in your browser...
start http://127.0.0.1:8765

echo.
echo Control Center is running.
echo.
timeout /t 2 /nobreak >nul
exit
