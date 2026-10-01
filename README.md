# Platform

A multi-tenant, metadata-driven application platform. Backend: Java 25, Spring Boot, Spring Modulith (modular
monolith), PostgreSQL, Redis, S3-compatible object storage. Frontend: Next.js (`platform-web`).

Planning documents live outside this repository (delivery plan and traceability). Decisions are recorded in
[docs/adr](docs/adr/README.md).

## Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| JDK | 25 (`JAVA_HOME` set) | `java -version` |
| Container runtime with Compose | any recent | `docker compose version` |
| Maven | not needed, the wrapper downloads 3.9.16 | `./mvnw -v` |
| Node.js and npm | Node 22.12 or newer (24 recommended, see `platform-web/.nvmrc`) | `node -v` |

## Build and test

```bash
./mvnw verify                      # compile, unit, architecture and integration tests, package
./mvnw test -DexcludedGroups=architecture   # unit tests only
./mvnw test -Dgroups=architecture           # architecture tests only
./mvnw verify -Pintegration-only            # integration tests only (need a running container runtime)
./mvnw -Pquality verify                     # adds static analysis
```

Run the packaged application:

```bash
# local services first: cd infra/local && ./init-env.ps1 && docker compose up -d
java -jar platform-app/target/platform-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
curl http://localhost:8080/api/v1/platform/status        # the API
curl http://localhost:8081/actuator/health/readiness     # probes and metrics, on their own port
```

Run the frontend (needs the application above): `cd platform-web && npm ci && npm run dev`, then open
`http://localhost:3000`.

## Local services

`infra/local/` starts PostgreSQL, Redis, S3-compatible storage and a mail catcher.
See [infra/local/README.md](infra/local/README.md).

## Layout

| Path | Contents |
|------|----------|
| `platform-app/` | The Spring Boot application; every logical module is a package under `app.platform` |
| `platform-shared-kernel/` | Identifiers and cross-cutting interfaces (secrets store) |
| `platform-api-contract/` | The published HTTP contract: paths, envelope, errors, paging and the committed OpenAPI document |
| `platform-web/` | The Next.js frontend, with the TypeScript API client generated from the OpenAPI document |
| `docs/` | ADRs (`docs/adr`), module map (`docs/modules.md`), spike findings (`docs/spikes`) |
| `infra/local/` | Local container environment |
| `config/` | Static-analysis configuration |
| `scripts/` | Repository helper scripts (migration mirror) |
| `spikes/` | Throwaway experiments, not part of the build |
| `.github/` | CI pipeline and dependency update configuration |

See [docs/engineering-guide.md](docs/engineering-guide.md) for the engineering rules, module list, commands and
coding standards.
