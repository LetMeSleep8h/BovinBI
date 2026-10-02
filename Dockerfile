# BovinBI 后端镜像(多阶段:构建层带 Maven+JDK,运行层仅 JRE)
# 构建: docker build -t bovinbi-backend .   (或直接 docker compose -f docker/docker-compose.yml up -d --build)

# ---- 构建层:先拷 pom 拉依赖(命中缓存时改代码不重新下载依赖) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# 阿里云镜像源:国内网络直连 Maven Central 极慢,构建会"卡住"十几分钟
COPY docker/maven-settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# ---- 运行层 ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/BovinBI-1.0.0.jar app.jar
ENV TZ=Asia/Shanghai
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
