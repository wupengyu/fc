@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion

set "MARIADB_HOME=%USERPROFILE%\tools\mariadb\mariadb-10.6.22-winx64"
set "MYSQL_ADMIN=%MARIADB_HOME%\bin\mariadb-admin.exe"

if not exist "%MYSQL_ADMIN%" (
    echo [ERROR] mysqladmin not found: %MYSQL_ADMIN%
    exit /b 1
)

tasklist /FI "IMAGENAME eq mariadbd.exe" | find /I "mariadbd.exe" >nul
if not %ERRORLEVEL%==0 (
    echo [INFO] MariaDB is not running.
    exit /b 0
)

echo [INFO] Stopping local MariaDB...
"%MYSQL_ADMIN%" -h 127.0.0.1 -P 3306 -u root -proot shutdown

if not %ERRORLEVEL%==0 (
    echo [ERROR] MariaDB failed to stop. Check the connection or password.
    exit /b 1
)

for /L %%I in (1,1,10) do (
    tasklist /FI "IMAGENAME eq mariadbd.exe" | find /I "mariadbd.exe" >nul
    if not !ERRORLEVEL!==0 (
        echo [OK] MariaDB stopped.
        exit /b 0
    )
    timeout /t 1 >nul
)

echo [WARN] Shutdown command succeeded, but mariadbd.exe is still exiting.
exit /b 0
