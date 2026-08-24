@echo off
echo.
echo [信息] 使用Jar命令运行EMS-Server工程。
echo.

cd %~dp0
cd ../witos-modules/ems_server/target

set JAVA_OPTS=-Xms512m -Xmx1024m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=512m
if "%EMS_SCHEDULE_ENABLED%"=="" set EMS_SCHEDULE_ENABLED=true

java -Dfile.encoding=utf-8 %JAVA_OPTS% -jar ems_server.jar

cd bin
pause
