# The edge sidecar image (Sprint 33, ADR-0286, ADR-0287): a reverse proxy with the platform's rules baked in.
# Build context: deploy/edge/
#   docker build -t platform-edge:local deploy/edge
# It holds no secret and no address: the one setting, TRUSTED_CIDR, is the network of the load balancer, given by the
# deployment. The same rules file is used by the rehearsal's ingress (infra/rehearsal), so what is tried there is what runs.

# The proxy is built here, from the release the builder image carries, with the one library that the release still ships
# in a vulnerable version replaced by the fixed one (the image scan names it). Remove the replace line when a proxy
# release ships the fixed version itself. The builder's own Go release has the standard-library fixes.
FROM caddy:2.11-builder-alpine AS build
RUN xcaddy build --replace golang.org/x/net=golang.org/x/net@v0.62.0 --output /usr/bin/caddy

FROM caddy:2.11-alpine
COPY --from=build /usr/bin/caddy /usr/bin/caddy
# Pick up operating-system security fixes published after the base image was built.
RUN apk upgrade --no-cache \
 && addgroup -S edge && adduser -S -G edge edge
COPY sidecar.Caddyfile /etc/caddy/Caddyfile
COPY api-rules.caddy /etc/caddy/api-rules.caddy
ARG GIT_COMMIT=unknown
ARG BUILD_TIME=unknown
LABEL org.opencontainers.image.revision=${GIT_COMMIT} \
      org.opencontainers.image.created=${BUILD_TIME}
# Caddy writes nothing it needs to keep (no certificates, no admin interface), but it wants somewhere to put its own
# state, so that is a folder of the unprivileged user.
ENV XDG_DATA_HOME=/tmp/caddy-data \
    XDG_CONFIG_HOME=/tmp/caddy-config
USER edge
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -q --spider http://127.0.0.1:8080/edge-ready || exit 1
CMD ["caddy", "run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]
