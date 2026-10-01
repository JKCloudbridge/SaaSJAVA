# Platform

A multi-tenant, metadata-driven application platform. Backend: Java 25, Spring Boot, Spring Modulith (modular
monolith), PostgreSQL, Redis, S3-compatible object storage. Frontend (from Sprint 1): Next.js.

Planning documents live outside this repository (delivery plan and traceability). Decisions are recorded in
[docs/adr](docs/adr/README.md).

## Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| JDK | 25 (`JAVA_HOME` set) | `java -version` |
| Container runtime with Compose | any recent | `docker compose version` |
| Maven | not needed, the wrapper downloads 3.9.16 | `./mvnw -v` |

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
java -jar platform-app/target/platform-app-0.1.0-SNAPSHOT.jar
curl http://localhost:8080/actuator/health
```

## Local services

`infra/local/` starts PostgreSQL, Redis, S3-compatible storage and a mail catcher.
See [infra/local/README.md](infra/local/README.md).

## Layout

| Path | Contents |
|------|----------|
| `platform-app/` | The Spring Boot application; every logical module is a package under `app.platform` |
| `platform-shared-kernel/` | Identifiers and cross-cutting interfaces (secrets store) |
| `platform-api-contract/` | The published HTTP contract |
| `docs/` | ADRs (`docs/adr`), module map (`docs/modules.md`), spike findings (`docs/spikes`) |
| `infra/local/` | Local container environment |
| `config/` | Static-analysis configuration |
| `scripts/` | Repository helper scripts (migration mirror) |
| `spikes/` | Throwaway experiments, not part of the build |
| `.github/` | CI pipeline and dependency update configuration |

See [docs/engineering-guide.md](docs/engineering-guide.md) for the engineering rules, module list, commands and
coding standards.
