@echo off
chcp 65001 >nul
setlocal

set "APP_HOME=%~dp0"

set "JAVA_HOME=%USERPROFILE%\tools\jdk21\jdk-21.0.10+7"
if not exist "%JAVA_HOME%\bin\java.exe" (
    set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot"
)

set "MAVEN_HOME=%USERPROFILE%\tools\maven\apache-maven-3.9.9"
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
    set "MAVEN_HOME="
)

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JDK 21 not found.
    echo [ERROR] Expected:
    echo         %USERPROFILE%\tools\jdk21\jdk-21.0.10+7
    echo         C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot
    exit /b 1
)

if "%MAVEN_HOME%"=="" (
    echo [ERROR] Maven not found.
    echo [ERROR] Expected: %USERPROFILE%\tools\maven\apache-maven-3.9.9
    exit /b 1
)

set "PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%"

echo ==========================================
echo   Build Executable Jar
echo ==========================================
echo APP_HOME   : %APP_HOME%
echo JAVA_HOME  : %JAVA_HOME%
echo MAVEN_HOME : %MAVEN_HOME%
echo.

pushd "%APP_HOME%"
call mvn -DskipTests package -s settings-temp.xml
set "EXIT_CODE=%ERRORLEVEL%"
popd

if not "%EXIT_CODE%"=="0" (
    echo.
    echo [ERROR] Build failed.
    exit /b %EXIT_CODE%
)

echo.
echo [OK] Build finished.
echo [OK] Jar: %APP_HOME%target\qian-xun-pro-wechat-http-demo-1.0.0.jar
exit /b 0
