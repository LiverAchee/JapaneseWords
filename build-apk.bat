@echo off
rem Build the Android APK (double-click, or run "build-apk.bat nopause").
rem   versioned copy is kept in dist\ ; nothing is copied to the phone sync folder.
cd /d "%~dp0android"
set JAVA_HOME=D:\Java
set ANDROID_HOME=D:\Android\Sdk
set GRADLE_USER_HOME=D:\Android\gradle-home
rem Java's dual-stack sockets cannot reach 127.0.0.1 on this PC, force IPv4 for the Gradle daemon.
set JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true
if not exist local.properties echo sdk.dir=D:/Android/Sdk> local.properties
rem Signing key + password stay out of git; first run creates them (keep them, or new builds cannot upgrade the installed app).
if not exist keystore.properties powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0make-keystore.ps1"
if errorlevel 1 goto err
call D:\Android\gradle-8.7\bin\gradle.bat --no-daemon -q -Duser.language=en assembleRelease
if errorlevel 1 goto err
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0publish-apk.ps1"
if errorlevel 1 goto err
if "%1"=="" pause
exit /b 0
:err
echo.
echo Build failed, see messages above.
if "%1"=="" pause
exit /b 1
