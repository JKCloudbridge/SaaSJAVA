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
# local services first: cd infra/local && ./init-env.ps1 && docker compose up -d && ./init-app-role.ps1
java -jar platform-app/target/platform-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
curl http://localhost:8080/api/v1/platform/status        # the API
curl http://localhost:8081/actuator/health/readiness     # probes and metrics, on their own port
```

Run the frontend (needs the application above): `cd platform-web && npm ci && npm run dev`, then open
`http://localhost:3000`.

## Local services

`infra/local/` starts PostgreSQL, Redis, S3-compatible storage and a mail catcher.
See [infra/local/README.md](infra/local/README.md).

## Tenancy and events

Organizations (tenants) live at `<slug>.<platform domain>`; on a developer machine open `http://tenant-a.localhost:3000`
(the `local` profile creates `tenant-a` and `tenant-b`). Tenant data is isolated by the application and by PostgreSQL row
level security, and modules react to each other through a transactional outbox. Contributors: read
[docs/tenancy.md](docs/tenancy.md) and [docs/tenant-isolation-testing.md](docs/tenant-isolation-testing.md).
The application connects as its own database role, created once per environment by the manual migration
`db/manual/M001__create_application_role.sql` (locally: `infra/local/init-app-role.ps1`).

## Layout

| Path | Contents |
|------|----------|
| `platform-app/` | The Spring Boot application; every logical module is a package under `app.platform` |
| `platform-shared-kernel/` | Identifiers and cross-cutting interfaces (secrets store) |
| `platform-api-contract/` | The published HTTP contract: paths, envelope, errors, paging and the committed OpenAPI document |
| `platform-web/` | The Next.js frontend, with the TypeScript API client generated from the OpenAPI document |
| `docs/` | ADRs (`docs/adr`), module map (`docs/modules.md`), tenancy and isolation-test guides, the metadata guide (`docs/metadata-guide.md`: how to add or change standard objects and fields) with the generated list of them (`docs/standard-objects.md`), spike findings (`docs/spikes`) |
| `db/manual/` | Manual migrations: scripts a person runs once per environment (the application role) |
| `infra/local/` | Local container environment |
| `config/` | Static-analysis configuration |
| `scripts/` | Repository helper scripts (migration mirror) |
| `spikes/` | Throwaway experiments, not part of the build |
| `.github/` | CI pipeline and dependency update configuration |

The engineering rules, commands and coding standards are summarized for contributors in the engineering guide kept
with the project notes; the decisions behind them are in [docs/adr](docs/adr/README.md).
