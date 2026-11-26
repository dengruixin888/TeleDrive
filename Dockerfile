# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY shared ./shared
COPY server ./server

ENV GRADLE_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m"
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && \
    ./gradlew :server:installDist -PtdlightNativesOs=linux -Dkotlin.compiler.execution.strategy=in-process --no-daemon --max-workers=2

FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app

# TDLight Linux natives (linux_amd64_gnu_ssl3) require OpenSSL 3 & CA certificates
RUN apt-get update && \
    apt-get install -y --no-install-recommends libssl3 ca-certificates curl && \
    rm -rf /var/lib/apt/lists/*

COPY --from=builder /workspace/server/build/install/server /app

ENV TELEDRIVE_HOST=0.0.0.0 \
    TELEDRIVE_PORT=8080 \
    TELEDRIVE_DATA_DIR=/data \
    TELEDRIVE_DOWNLOAD_DIR=/downloads \
    TELEDRIVE_POLL_INTERVAL_MINUTES=0 \
    TELEDRIVE_SYNC_ON_START=false

VOLUME ["/data", "/downloads"]
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD curl -fsS http://127.0.0.1:${TELEDRIVE_PORT}/health || exit 1

ENTRYPOINT ["/app/bin/server"]
