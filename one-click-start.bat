@echo off
chcp 65001 >nul
setlocal

cd /d "%~dp0"

echo ==========================================
echo   One Click Start - WeChat Parser Service
echo ==========================================
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0one-click-start.ps1"
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if "%EXIT_CODE%"=="0" (
    echo [OK] Service start script finished.
) else (
    echo [ERROR] Service start failed, exit code %EXIT_CODE%.
)
echo.
pause
exit /b %EXIT_CODE%
