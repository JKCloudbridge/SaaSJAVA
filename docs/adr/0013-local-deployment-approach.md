# ADR-0013: Deployment target for local development (decision D5, local part)

- **Status:** Accepted for local use; the production part of D5 stays open until Sprint 33
- **Date:** 2026-10-01
- **Decision IDs:** D5
- **Sprint:** S1

## Context

Decision D5 ("containers on an orchestrator of your choice") closes in two steps: how the platform runs on a developer's
machine now (Sprint 1), and where and how it runs in production (Sprint 33). The local step should already commit the
application to the properties that make any orchestrator work, without choosing one.

## Decision

1. **Local runtime: OCI containers started with Compose.** `infra/local/docker-compose.yml` runs the platform's
   dependencies: PostgreSQL, Redis, S3-compatible object storage, a mail catcher and a trace viewer. Everything binds to
   `127.0.0.1`; credentials are random per machine (`init-env.ps1`) and never committed.
2. **The application runs three ways, all with the same configuration contract** (environment variables, see
   [ADR-0009](0009-migration-framework-and-database-roles.md)):
   - from the IDE or as a jar with `--spring.profiles.active=local` (the usual developer loop; reads `infra/local/.env`);
   - as the container image on the Compose network: `docker compose --profile app up -d` after
     `docker build -t platform-app:local platform-app` (closest to a deployment; uses the `prod` profile);
   - in CI, where the image is started against a real database and its readiness and status endpoint are checked.
3. **The image is orchestrator-neutral.** Properties that every orchestrator can rely on, all tested or checked in CI:
   one process, no local state, configuration only from environment variables, non-root user, UTC, graceful shutdown,
   liveness and readiness probes and metrics on a separate port (8081) from the API (8080), structured logs on standard
   output, and a health check in the image.
4. **The web frontend runs as a development server or a production build** (`npm run dev`, `npm run build && npm start`)
   behind the same-origin forwarding described in [ADR-0011](0011-api-conventions.md). A web container image is built when
   deployment is designed (Sprint 33); nothing in the app needs it earlier.
5. **Not decided here (Sprint 33):** the production orchestrator, infrastructure as code, environment separation, the
   ingress and edge (TLS, sampling, routing), the secrets store implementation (D4), registry and release process.

## Consequences

- A new developer needs a container runtime, JDK 25 and Node, and gets a working stack with three commands.
- The production decision can pick any orchestrator without changing the application.
- The local Compose file is not production guidance: single instances, no replication, in-memory trace storage.

## Alternatives considered

- **Install the dependencies natively:** differs per machine and from production; containers give the same versions
  everywhere.
- **Pick the production orchestrator now:** premature; it would be decided without measurements or operational requirements.
- **Run the Next.js app only in the browser's dev server forever:** fine for development, but the build is part of CI
  already; the image waits for deployment design.

## References

- `infra/local/` (`docker-compose.yml`, `README.md`, `init-env.ps1`, `verify.ps1`), `platform-app/Dockerfile`,
  `.github/workflows/ci.yml` (job "Container image")
- Delivery plan: decision table (D5), Sprint 33
