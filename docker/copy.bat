@echo off
chcp 65001 >nul & cls
echo.
echo [信息] 复制文件到Docker目录
echo.

%~d0
cd %~dp0

cd ..
echo 编译后端
start /wait cmd /c "mvn clean package -P prod -Dmaven.test.skip=true"
echo 编译前端
cd witos-ui
start /wait cmd /c "npm install"
start /wait cmd /c "npm run build:prod"
cd ..\docker

echo 复制 sql
xcopy ..\sql\witos_platform.sql .\mysql\db  /y
xcopy ..\sql\witos_config.sql .\mysql\db  /y

echo 复制 html
xcopy ..\witos-ui\dist .\nginx\html\dist  /s /e /y

echo 复制 ems-register
xcopy ..\witos-register\target\ems-register.jar .\nacos\jar  /y

echo 复制 ems-gateway
xcopy ..\witos-gateway\target\ems-gateway.jar .\witos\gateway\jar  /y

echo 复制 ems-auth
xcopy ..\witos-auth\target\ems-auth.jar .\witos\auth\jar  /y

echo 复制 ems-demo
xcopy ..\witos-demo\target\ems-demo.jar .\witos\demo\jar  /y

echo 复制 ems-monitor
xcopy ..\witos-visual\witos-monitor\target\ems-monitor.jar  .\witos\visual\monitor\jar  /y

echo 复制 ems-system
xcopy ..\witos-modules\witos-system\target\ems-system.jar .\witos\modules\system\jar  /y

echo 复制 ems-file
xcopy ..\witos-modules\witos-file\target\ems-file.jar .\witos\modules\file\jar  /y

echo 复制 ems-gen
xcopy ..\witos-modules\witos-gen\target\ems-gen.jar .\witos\modules\gen\jar  /y

echo 复制 ems-job
xcopy ..\witos-modules\witos-job\target\ems-job.jar .\witos\modules\job\jar  /y

pause
