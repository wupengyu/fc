@echo off
chcp 65001 >nul
setlocal

set "APP_HOME=%~dp0"
set "JAR_PATH=%APP_HOME%target\qian-xun-pro-wechat-http-demo-1.0.0.jar"

set "JAVA_HOME=%USERPROFILE%\tools\jdk21\jdk-21.0.10+7"
if not exist "%JAVA_HOME%\bin\java.exe" (
    set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot"
)

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JDK 21 not found.
    echo [ERROR] Expected:
    echo         %USERPROFILE%\tools\jdk21\jdk-21.0.10+7
    echo         C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot
    exit /b 1
)

if not exist "%JAR_PATH%" (
    echo [ERROR] Jar not found: %JAR_PATH%
    echo [INFO] Run build-jar.bat first.
    exit /b 1
)

if "%JAVA_OPTS%"=="" (
    set "JAVA_OPTS=-Xms256m -Xmx512m"
)

set "PATH=%JAVA_HOME%\bin;%PATH%"

echo ==========================================
echo   Run Executable Jar
echo ==========================================
echo APP_HOME  : %APP_HOME%
echo JAVA_HOME : %JAVA_HOME%
echo JAR       : %JAR_PATH%
echo URL       : http://127.0.0.1:8989/wechat/stats.html
echo CALLBACK  : http://127.0.0.1:8989/wechat/callback
echo.

pushd "%APP_HOME%"
java %JAVA_OPTS% -jar "%JAR_PATH%" %*
set "EXIT_CODE=%ERRORLEVEL%"
popd

exit /b %EXIT_CODE%
