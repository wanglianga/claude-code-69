# ---- 构建阶段 ----
FROM gradle:8.10.2-jdk17-jammy AS build
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle settings.gradle.kts build.gradle.kts gradle.properties ./
# 先预热 Gradle 依赖缓存
RUN mkdir -p src/main/kotlin src/main/resources \
    && echo 'package stub' > src/main/kotlin/Stub.kt \
    && gradle --no-daemon compileKotlin || true
COPY --chown=gradle:gradle src ./src
RUN gradle --no-daemon clean build -x test

# ---- 运行阶段 ----
FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system app && useradd --system --gid app --home-dir /app --create-home app
WORKDIR /app
COPY --from=build /home/gradle/src/build/libs/*.jar /app/app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=12 \
  CMD ["bash","-c","exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /health HTTP/1.1\\r\\nHost: local\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q '200 OK' <&3"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
