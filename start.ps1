# PowerShell launcher for Llama Server & Pi Control Center
Write-Host "=========================================================================" -ForegroundColor Cyan
Write-Host "   Llama Server & Pi Control Center" -ForegroundColor Yellow
Write-Host "   Unified Server (Angular 21 Zoneless UI + Java 21 Backend)" -ForegroundColor Gray
Write-Host "=========================================================================" -ForegroundColor Cyan

$root = Split-Path -Parent $MyInvocation.MyCommand.Path

# Start Unified Java Server
Write-Host "[1/1] Starting Java 21 Unified Server on http://127.0.0.1:8765 ..." -ForegroundColor Green
Start-Process -FilePath "cmd.exe" -ArgumentList "/k", "title Llama and Pi Unified Server (Port 8765) && cd /d `"$root\backend`" && java BackendServer.java"

Start-Sleep -Seconds 1

# Open default browser
Write-Host "Opening http://127.0.0.1:8765 in your browser..." -ForegroundColor Cyan
Start-Process "http://127.0.0.1:8765"

Write-Host "`nControl Center launched successfully." -ForegroundColor Green
