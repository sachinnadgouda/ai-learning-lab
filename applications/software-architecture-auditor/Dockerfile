# Stage 1: Build distribution on pinned JDK 21
FROM eclipse-temurin:21.0.6_7-jdk-alpine AS builder

WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x ./gradlew && ./gradlew --no-daemon dependencies

COPY src ./src
RUN ./gradlew --no-daemon installDist -x test

# Stage 2: Minimal non-root JRE 21 runtime
FROM eclipse-temurin:21.0.6_7-jre-alpine

RUN addgroup -S auditor && adduser -S -G auditor auditor \
    && mkdir -p /app /data /repositories \
    && chown -R auditor:auditor /app /data /repositories

WORKDIR /app
COPY --from=builder --chown=auditor:auditor /workspace/build/install/arch-auditor /app/
COPY --chown=auditor:auditor examples /repositories/examples

USER auditor

ENV AUDITOR_HOST=0.0.0.0 \
    AUDITOR_PORT=8083 \
    AUDITOR_DB_PATH=/data/auditor.db \
    AUDITOR_ALLOWED_ROOTS=/repositories \
    AI_PROVIDER=none

EXPOSE 8083

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8083/health || exit 1

ENTRYPOINT ["/app/bin/arch-auditor"]
CMD ["serve"]
