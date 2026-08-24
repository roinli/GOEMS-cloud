#!/bin/sh

# 复制项目的文件到对应docker路径，便于一键生成镜像。
usage() {
	echo "Usage: sh copy.sh"
	exit 1
}

echo "begin package "
#打包开始
cd ..
mvn clean install -Dmaven.test.skip=true
#前端
cd ./witos-ui
npm install --registry=https://registry.npmmirror.com
npm run build:prod
cd ../docker
# copy sql
echo "begin copy sql "
cp ../sql/witos_platform.sql ./mysql/db
cp ../sql/witos_config.sql ./mysql/db

# copy html
echo "begin copy html "
rm -rf ./nginx/html/dist
cp -rp ../witos-ui/dist/** ./nginx/html/dist


# copy jar
echo "begin copy ems-register "
cp ../witos-register/target/ems-register.jar ./nacos/jar

echo "begin copy ems-gateway "
cp ../witos-gateway/target/ems-gateway.jar ./witos/gateway/jar

echo "begin copy ems-auth "
cp ../witos-auth/target/ems-auth.jar ./witos/auth/jar

echo "begin copy ems-demo "
cp ../witos-demo/target/ems-demo.jar ./witos/demo/jar

echo "begin copy ems-monitor "
cp ../witos-visual/witos-monitor/target/ems-monitor.jar  ./witos/visual/monitor/jar

echo "begin copy ems-system "
cp ../witos-modules/witos-system/target/ems-system.jar ./witos/modules/system/jar

echo "begin copy ems-file "
cp ../witos-modules/witos-file/target/ems-file.jar ./witos/modules/file/jar

echo "begin copy ems-gen "
cp ../witos-modules/witos-gen/target/ems-gen.jar ./witos/modules/gen/jar

echo "begin copy ems-job "
cp ../witos-modules/witos-job/target/ems-job.jar ./witos/modules/job/jar

