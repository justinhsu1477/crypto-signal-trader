# === Stage 1: Build ===
FROM gradle:8.13-jdk17 AS builder
WORKDIR /app

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

RUN mkdir -p /app/data

COPY --from=builder /app/build/libs/crypto-signal-trader-1.0.0.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
