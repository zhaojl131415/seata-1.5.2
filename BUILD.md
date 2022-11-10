启动server端, 编译报错:
提示: protobuf包下generated不存在, 由于seata-serializer-protobuf项目没有编译造成的，

只需要我们idea下载安装Protobuf Support插件，然后重启idea，然后在idea右侧maven菜单中，双击如下protobuf-compile执行就可以生成protobuf项目的java代码

源码编译:
1. 安装插件:
Idea安装Protobuf Support插件后重启
2. 编译Protobuf, 以下选择一个执行:
   2.1 运行Idea Maven窗口 seata-serializer -> seata-serializer-protobuf -> Plugins -> protobuf -> protobuf:compile

   2.2 执行命令: mvn clean install -DskipTests=true
注意: MacBook M1/M2芯片 会提示 找不到com.google.protobuf:protoc:exe:osx-aarch_64:3.3.0这个包, 我们通过maven仓库地址可以看到确实不存在这个包, 所以需要修改依赖指定使用x86_64的包.
所以应当执行: mvn clean package -DskipTests -Dos.detected.classifier=osx-x86_64
protobuf对应的Maven仓库地址: https://repo.maven.apache.org/maven2/com/google/protobuf/protoc