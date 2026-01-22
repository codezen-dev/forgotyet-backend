# 基础镜像
FROM openjdk:17-jdk-slim

# 作者
LABEL maintainer="ForgotYet"

# 设置工作目录
WORKDIR /app

# 挂载数据卷目录（虽然 docker run 会映射，但声明一下是个好习惯）
VOLUME /app/data

# 拷贝 JAR 包
COPY target/forgotyet-backend-0.0.1-SNAPSHOT.jar app.jar

# 暴露端口
EXPOSE 8078

# 启动命令 (关键：加了内存限制 -Xmx512m)
# 这样即使服务器负载高，它也不会无限制吃内存
ENTRYPOINT ["java", "-Xms256m", "-Xmx512m", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]