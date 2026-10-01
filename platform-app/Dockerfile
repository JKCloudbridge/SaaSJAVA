# Container image of the platform application.
# Build context: platform-app/ after `./mvnw package` (the jar must exist in target/).
#   docker build -t platform-app:local platform-app

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

ENV TZ=UTC
USER platform
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=3 \
  CMD wget -q --spider http://127.0.0.1:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Duser.timezone=UTC", "-jar", "app.jar"]
