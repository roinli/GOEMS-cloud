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
cd ../docker

echo 复制 sql
xcopy ..\sql\witos_platform.sql .\mysql\db  /y
xcopy ..\sql\witos_config.sql .\mysql\db  /y

echo 复制 html
xcopy ..\witos-ui\dist .\nginx\html\dist  /s /e /y

echo 复制 witos-nacos
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

@REM 前端打包成镜像
cd ./nginx
docker build -t witos/witos-nginx:v1.0 .

@REM nacos打包成镜像
cd ../nacos
docker build -t ems/ems-register:v1.0 .

@REM 打包网关镜像 (修改构建上下文)
cd ../witos/gateway
docker build -t ems/ems-gateway:v1.0 .

@REM 打包认证镜像 (修改构建上下文)
cd ../../witos/auth
docker build -t ems/ems-auth:v1.0 .

@REM 打包系统模块镜像 (修改构建上下文)
cd ../../witos/modules/system
docker build -t ems/ems-system:v1.0 .

@REM 打包文件服务镜像 (修改构建上下文)
cd ../file
docker build -t ems/ems-file:v1.0 .

@REM 打包定时任务镜像
cd ../job
docker build -t ems/ems-job:v1.0 .

@REM 打包监视镜像
cd ../../visual/monitor
docker build -t ems/ems-monitor:v1.0 .

@REM 打包demo镜像
cd ../../demo
docker build -t ems/ems-demo:v1.0 .

@REM 打包代码生成镜像
cd ../modules/gen
docker build -t ems/ems-gen:v1.0 .

pause
