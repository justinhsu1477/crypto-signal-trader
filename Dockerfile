# === Stage 1: Build ===
FROM gradle:8.13-jdk17 AS builder
WORKDIR /app

# 限制 Gradle JVM 記憶體，避免在小機器 OOM
ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx512m -Dorg.gradle.workers.max=2"

# Copy Gradle build files first for layer caching
COPY build.gradle settings.gradle ./
COPY gradle/ gradle/
COPY gradlew ./

# Download dependencies (cached unless build.gradle changes)
RUN ./gradlew dependencies --no-daemon || true

# Copy source and build
COPY src/ src/
RUN ./gradlew build -x test --no-daemon

# === Stage 2: Run ===
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# CJK 字型（Rich Menu 圖片產生需要）+ fontconfig
RUN apt-get update && \
    apt-get install -y --no-install-recommends fontconfig fonts-noto-cjk && \
    rm -rf /var/lib/apt/lists/* && \
    fc-cache -f

RUN mkdir -p /app/data /app/logs

COPY --from=builder /app/build/libs/crypto-signal-trader-1.0.0.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
