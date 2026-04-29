@echo off
chcp 65001 >nul
setlocal

set "MARIADB_HOME=%USERPROFILE%\tools\mariadb\mariadb-10.6.22-winx64"
set "MARIADB_DATA=%USERPROFILE%\tools\mariadb-data"
set "MARIADB_EXE=%MARIADB_HOME%\bin\mariadbd.exe"
set "MARIADB_INI=%MARIADB_DATA%\my.ini"
set "MARIADB_STDOUT=%MARIADB_DATA%\mariadbd.stdout.log"
set "MARIADB_STDERR=%MARIADB_DATA%\mariadbd.stderr.log"

if not exist "%MARIADB_EXE%" (
    echo [ERROR] MariaDB executable not found: %MARIADB_EXE%
    exit /b 1
)

if not exist "%MARIADB_INI%" (
    echo [ERROR] MariaDB config not found: %MARIADB_INI%
    exit /b 1
)

tasklist /FI "IMAGENAME eq mariadbd.exe" | find /I "mariadbd.exe" >nul
if %ERRORLEVEL%==0 (
    echo [INFO] MariaDB is already running.
    exit /b 0
)

echo [INFO] Starting local MariaDB...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -WindowStyle Hidden -FilePath '%MARIADB_EXE%' -ArgumentList '--defaults-file=%MARIADB_INI%','--bind-address=0.0.0.0','--character-set-server=utf8mb4','--collation-server=utf8mb4_general_ci' -RedirectStandardOutput '%MARIADB_STDOUT%' -RedirectStandardError '%MARIADB_STDERR%'"
timeout /t 5 >nul

tasklist /FI "IMAGENAME eq mariadbd.exe" | find /I "mariadbd.exe" >nul
if %ERRORLEVEL%==0 (
    echo [OK] MariaDB started at 0.0.0.0:3306
    exit /b 0
)

echo [ERROR] MariaDB failed to start. Check the data directory or error log.
exit /b 1
