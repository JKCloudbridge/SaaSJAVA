# Container image of the platform application.
# Build context: platform-app/ after `./mvnw package` (the jar must exist in target/).
#   docker build -t platform-app:local platform-app
#
# The image takes all of its settings from environment variables and runs the production profile. It needs a
# PostgreSQL to start (the readiness probe only turns UP once the migrations ran and the database answers):
#   PLATFORM_DB_URL, PLATFORM_DB_OWNER_USER, PLATFORM_DB_OWNER_PASSWORD,
#   PLATFORM_DB_APP_USER, PLATFORM_DB_APP_PASSWORD
# Optional: PLATFORM_ENVIRONMENT, PLATFORM_TRACE_EXPORT, PLATFORM_TRACE_ENDPOINT, PLATFORM_TRACE_SAMPLING.
# Local run with everything wired: `docker compose --profile app up -d` in infra/local.

# Stage 1: split the executable jar into layers so dependency layers are cached between builds.
FROM eclipse-temurin:25.0.4.1_1-jre-alpine AS extract
WORKDIR /build
COPY target/platform-app-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# Stage 2: runtime image. Non-root user, UTC, container-aware memory settings.
FROM eclipse-temurin:25.0.4.1_1-jre-alpine
# Pick up operating-system security fixes published after the base image was built.
RUN apk upgrade --no-cache \
 && addgroup -S platform && adduser -S -G platform platform
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./

ENV TZ=UTC \
    SPRING_PROFILES_ACTIVE=prod
USER platform
# 8080: the API. 8081: probes and metrics, to be reachable by the platform's operators only.
EXPOSE 8080 8081
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=3 \
  CMD wget -q --spider http://127.0.0.1:8081/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Duser.timezone=UTC", "-jar", "app.jar"]
