启动server端, 编译报错:
提示: protobuf包下generated不存在, 由于seata-serializer-protobuf项目没有编译造成的，

只需要我们idea下载安装Protobuf Support插件，然后重启idea，然后在idea右侧maven菜单中，双击如下protobuf-compile执行就可以生成protobuf项目的java代码

源码编译:
1. 安装插件:
Idea安装Protobuf Support插件后重启
2. 编译Protobuf, 以下选择一个执行:
   2.1 运行Idea Maven窗口 seata-serializer -> seata-serializer-protobuf -> Plugins -> protobuf -> protobuf:compile

   2.2 执行命令: mvn clean install -DskipTests=true
注意: MacBook (M1/M2芯片?) 会提示 找不到com.google.protobuf:protoc:exe:osx-aarch_64:3.3.0这个包, 我们通过maven仓库地址可以看到确实不存在这个包, 所以需要修改依赖指定使用x86_64的包.
所以应当执行: mvn clean package -DskipTests -Dos.detected.classifier=osx-x86_64
   或者在pom文件中，增加属性：<os.detected.classifier>osx-x86_64</os.detected.classifier>
protobuf对应的Maven仓库地址: https://repo.maven.apache.org/maven2/com/google/protobuf/protoc

seata服务端: docker部署
1. 下载镜像：
docker pull seataio/seata-server
2. 容器运行
docker run --name seata-server \
-p 8091:8091 \
-v /Users/zhaojinliang/docker/seata/conf/registry.conf:/seata-server/resources/registry.conf \
-v /Users/zhaojinliang/docker/seata/conf/application.yml:/seata-server/resources/application.yml \
-e SEATA_IP=192.168.110.228 \
-e SEATA_PORT=8091 \
-d seataio/seata-server

注意: 配置文件项目中存在, 可以直接从项目中拷贝, 替换到docker对应的宿主机挂载目录
registry.conf: script/client/conf/registry.conf
application.yml: server/src/main/resources/application.yml