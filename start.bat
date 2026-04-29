@echo off
chcp 65001 >nul
setlocal

echo ==========================================
echo   qian-xun-pro-wechat-http-demo 启动脚本
echo ==========================================
echo.

set "JAVA_HOME=%USERPROFILE%\tools\jdk21\jdk-21.0.10+7"
if not exist "%JAVA_HOME%\bin\java.exe" (
    set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot"
)

set "MAVEN_HOME=%USERPROFILE%\tools\maven\apache-maven-3.9.9"
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
    set "MAVEN_HOME="
)

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [错误] 未找到可用的 JDK 21，请先安装 Java 环境。
    exit /b 1
)

if "%MAVEN_HOME%"=="" (
    echo [错误] 未找到可用的 Maven，请先安装 Maven 环境。
    exit /b 1
)

set "PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%"
set MAVEN_OPTS=-Xms256m -Xmx512m

echo [配置信息]
echo JAVA_HOME: %JAVA_HOME%
echo MAVEN_HOME: %MAVEN_HOME%
echo 端口: 8989
echo 回调地址: http://127.0.0.1:8989/wechat/callback
echo.
echo [实时日志输出]
echo ==========================================

mvn spring-boot:run -s settings-temp.xml 2>&1

pause
