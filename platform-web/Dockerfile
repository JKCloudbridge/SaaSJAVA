# Container image of the web application (Next.js, production build).
# Build context: platform-web/
#   docker build -t platform-web:local platform-web
#
# The image runs the built output, not the development server. It holds no secret and no host name: in a deployment
# the ingress sends /api to the platform application and everything else here (the rewrites of next.config.ts are
# only for development and are not reached). One image for every environment.

# Stage 1: the dependencies, from the committed lock file only.
FROM node:24-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

# Stage 2: the production build.
FROM node:24-alpine AS build
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

# Stage 3: runtime. Only the traced server, the static files and the public folder; a user without rights.
FROM node:24-alpine AS run
# Pick up operating-system security fixes published after the base image was built.
RUN apk upgrade --no-cache \
 && addgroup -S web && adduser -S -G web web
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0 \
    TZ=UTC
COPY --from=build --chown=web:web /app/.next/standalone ./
COPY --from=build --chown=web:web /app/.next/static ./.next/static
COPY --from=build --chown=web:web /app/public ./public
USER web
EXPOSE 3000
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=3 \
  CMD wget -q --spider http://127.0.0.1:3000/healthz || exit 1
CMD ["node", "server.js"]
