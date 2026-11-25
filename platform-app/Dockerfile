# Container image of the platform application.
# Build context: platform-app/ after `./mvnw package` (the jar must exist in target/).
#   docker build -t platform-app:local platform-app
#   (a release build also gives the stamp: --build-arg GIT_COMMIT=<hexadecimal commit> --build-arg BUILD_TIME=<instant>)
#
# The image takes all of its settings from environment variables and runs the production profile. It has two commands
# (entrypoint.sh): `serve`, the default, starts the application; `migrate` runs the schema migrations as the owner role
# and exits (the migration job of a deployment, Sprint 33, ADR-0288). One image serves every environment.
#
# serve needs a PostgreSQL to start (the readiness probe only turns UP once the database answers):
#   PLATFORM_DB_URL, PLATFORM_DB_APP_USER, PLATFORM_DB_APP_PASSWORD (the role manual migration M001 creates; run it before
#   the first start), PLATFORM_BASE_DOMAIN (the platform domain; organizations live at <slug>.<domain>), and, unless the
#   migration job ran first and PLATFORM_MIGRATE_ON_START=false, PLATFORM_DB_OWNER_USER and PLATFORM_DB_OWNER_PASSWORD.
# Every setting is listed in docs/runbooks/environments.md.
# Local run with everything wired: `docker compose --profile app up -d` in infra/local.

# Stage 1: split the executable jar into layers so dependency layers are cached between builds.
FROM eclipse-temurin:25.0.4_7-jre-alpine AS extract
WORKDIR /build
COPY target/platform-app-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# Stage 2: runtime image. Non-root user, UTC, container-aware memory settings.
FROM eclipse-temurin:25.0.4_7-jre-alpine
# Pick up operating-system security fixes published after the base image was built.
RUN apk upgrade --no-cache \
 && addgroup -S platform && adduser -S -G platform platform
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
COPY --chmod=0555 entrypoint.sh /app/entrypoint.sh

# Which release this is (ADR-0287): shown in /actuator/info and in one log line at start. Neither value is a secret.
ARG GIT_COMMIT=unknown
ARG BUILD_TIME=unknown
ENV TZ=UTC \
    SPRING_PROFILES_ACTIVE=prod \
    PLATFORM_BUILD_COMMIT=${GIT_COMMIT} \
    PLATFORM_BUILD_TIME=${BUILD_TIME}
LABEL org.opencontainers.image.revision=${GIT_COMMIT} \
      org.opencontainers.image.created=${BUILD_TIME}
USER platform
# 8080: the API. 8081: probes and metrics, to be reachable by the platform's operators only.
EXPOSE 8080 8081
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=3 \
  CMD wget -q --spider http://127.0.0.1:8081/actuator/health/liveness || exit 1
# exec form: the java process is the one that receives the stop signal and drains (ADR-0289).
ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["serve"]
