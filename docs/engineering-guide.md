# Engineering guide

Multi-tenant, metadata-driven application platform. Modular monolith. This is what a contributor needs inside
this repository: commands, module rules, standing architecture rules, the Definition of Done and coding standards.

## Stack and versions

Java 25 (LTS), Spring Boot 4.1.1, Spring Security 7.1, Spring Modulith 2.1.1, PostgreSQL 18, Redis 8,
S3-compatible object storage, Maven 3.9.16 via the wrapper. Frontend (`platform-web`): Next.js 16 (App Router),
React 19, TypeScript 5.9, Node 24 (see `platform-web/.nvmrc`; 22.12 or newer works).

## Commands

Run from the repository root. JDK 25 must be the active Java (`java -version`).

| Task | Command |
|------|---------|
| Everything (compile, unit, architecture, integration, package) | `./mvnw verify` |
| What CI's backend stages run, including static analysis | `./mvnw -Pquality clean verify` |
| Fast loop, no containers | `./mvnw test` |
| Unit tests only | `./mvnw test -DexcludedGroups=architecture` |
| Architecture tests only | `./mvnw test -Dgroups=architecture` |
| Integration tests only (needs a container runtime) | `./mvnw verify -Pintegration-only` |
| Static analysis | `./mvnw -Pquality -DskipTests -DskipITs verify` |
| One module | `./mvnw -pl platform-app -am verify` |
| Run the app (local profile, needs the local services) | `java -jar platform-app/target/platform-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=local` |
| Local services | `cd infra/local; ./init-env.ps1; docker compose up -d; ./verify.ps1` |
| Regenerate the committed OpenAPI document after an API change | `./scripts/update-openapi.ps1` (or `.sh`) |
| Frontend: install, then everything CI checks | `cd platform-web; npm ci; npm run verify` |
| Frontend: lint, types, tests, build | `npm run lint`, `npm run typecheck`, `npm test`, `npm run build` |
| Frontend: regenerate / check the TypeScript API client | `npm run api:generate` / `npm run api:check` |
| Frontend dev server (proxies `/api` to the backend) | `npm run dev` then open `http://localhost:3000` |
| Spike tests | `./mvnw -f spikes/d2-auth-provider/pom.xml test` |
| Container image | `./mvnw -DskipTests package` then `docker build -t platform-app:local platform-app` |
| Refresh the migrations mirror | `./scripts/sync-migrations.ps1` (check: add `-Check`) |

CI (`.github/workflows/ci.yml`) runs the same stages: build, unit, architecture, integration, static analysis,
frontend (lint, types, tests, generated-client check, build, audit), dependency vulnerability scan, container image
build with a database-backed smoke test and scan. A change is done only when all pass.

## Modules

Logical modules are packages under `app.platform` in `platform-app` (details and allowed dependencies in
[modules.md](modules.md); decision in [adr/0001-modular-monolith.md](adr/0001-modular-monolith.md)):

`tenant`, `identity`, `licensing`, `security`, `metadata`, `data`, `application`, `workflow`, `approval`,
`notification`, `integration`, `audit`, `platformadmin` (platform admin), `web` and `observability` (infrastructure of
the HTTP layer and of logging, tracing and error tracking), `sharedkernel` (separate Maven module).

Maven modules: `platform-app`, `platform-shared-kernel`, `platform-api-contract` (package `app.platformapi`,
depends on nothing in the platform: paths, headers, envelope, error codes, paging, and the committed OpenAPI document).
The frontend is `platform-web` (npm, not part of the Maven build).

Rules enforced by tests (they fail the build):
- A module may use only another module's root package (its public API); sub-packages are internal.
- A module may depend only on the modules named in its `@ApplicationModule(allowedDependencies = ...)`.
- No cycles. Only `integration` may reference `app.platform.integration.adapter..`.
- Adding a dependency edge = edit that module's `package-info.java` and `docs/modules.md` in the same change.
- Never read or write another module's tables.
- Business modules do not depend on `web`; every controller endpoint returns an API envelope type, a `ResponseEntity` or nothing.

## Standing architecture rules

- The backend is authoritative: frontend validation and permission checks are UX only.
- The tenant ID is never trusted from the browser; derive it from authenticated identity, membership and host.
- Core modules never reference a specific external system; only `integration` knows adapters.
- No arbitrary SQL or executable code from tenant metadata.
- Licence, permission and feature entitlement are three separate mechanisms.
- No secrets in code, configuration, logs or metadata tables (secrets interface in `sharedkernel`).
- Tenant data is protected by application filtering **and** PostgreSQL row level security (ADR-0003).

## API, database and observability conventions (Sprint 1)

Short form; the decisions are in ADR-0009 to ADR-0013.

- **API** ([ADR-0011](adr/0011-api-conventions.md)): paths under `/api/v1`; success is `{"data": ...}`, a page adds
  `"pagination"`, a failure is `{"error": {code, message, fields?, requestId, traceId?}}` with a stable `ErrorCode`.
  Throw `ApiException` to fail on purpose; never put internals in its message. Take paging as a `PageRequest` parameter
  and return `ApiPageResponse`. After any controller or contract change run `scripts/update-openapi`, then
  `npm run api:generate` in `platform-web`, and commit all of it; CI fails otherwise.
- **Database** ([ADR-0009](adr/0009-migration-framework-and-database-roles.md), [ADR-0010](adr/0010-base-schema-conventions.md)):
  migrations are `V<nnn>__<snake_case>.sql` in `platform-app/src/main/resources/db/migration` (consecutive from 001,
  LF line endings, never edited after release), forward-only and expand/contract. Every table has the base columns
  and the `platform_row_guard` trigger (copy the template in ADR-0010); unique indexes are partial
  (`where deleted_at is null`). Every update carries the version. Run `scripts/sync-migrations.ps1` after any change.
- **Observability** ([ADR-0012](adr/0012-observability.md)): log through SLF4J only; the request ID, trace ID and
  tenant ID come from the logging context (`LogContext`), do not add them by hand. Never log bodies, tokens,
  passwords or personal data, and never put exception messages from libraries into responses or logs. Report unexpected
  errors through `ErrorTracker`.

## Configuration and environments

The base `application.yml` holds no secrets and no hosts. Profiles: `local` (developer machine), `test` (automated
tests, supplied by the test harness), `prod` (every deployed environment, the container image). A variable without a
default is mandatory.

| Variable | Used by | Meaning |
|----------|---------|---------|
| `PLATFORM_DB_URL` | prod | JDBC URL of PostgreSQL |
| `PLATFORM_DB_OWNER_USER`, `PLATFORM_DB_OWNER_PASSWORD` | prod | Owner role: runs the migrations |
| `PLATFORM_DB_APP_USER`, `PLATFORM_DB_APP_PASSWORD` | prod | Application role: the runtime pool; no schema rights |
| `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`, `POSTGRES_PORT` | local | The names in `infra/local/.env`; the file is read directly |
| `PLATFORM_ENVIRONMENT` | any | Label on metrics (`environment` tag) |
| `PLATFORM_HTTP_PORT`, `PLATFORM_MANAGEMENT_PORT` | any | API port (8080) and probes and metrics port (8081) |
| `PLATFORM_TRACE_EXPORT`, `PLATFORM_TRACE_ENDPOINT`, `PLATFORM_TRACE_SAMPLING` | any | Ship spans (default off), where to, sampling (default 0.1) |
| `PLATFORM_API_ORIGIN`, `PLATFORM_TRACE_COLLECTOR_ORIGIN`, `NEXT_PUBLIC_TRACE_EXPORT` | `platform-web` | Where Next.js forwards `/api` and `/telemetry`; whether the browser ships spans |

Everything with the prefix `NEXT_PUBLIC_` is sent to browsers: nothing secret ever goes there.

## Definition of Done (every story)

CI green; integration tests on a real PostgreSQL container (never an in-memory substitute); a tenant-isolation
test for every tenant-scoped table or endpoint; an authorization test (allowed, denied, cross-tenant) for every
endpoint; schema changes as backward-compatible versioned migrations; structured logs with request and tenant
IDs and audit events for security/config/data changes; OpenAPI updated; ADR or runbook updated when a
decision or operational behaviour changes; no secrets anywhere.

## Coding standards

- Java 25. Prefer records for immutable data, sealed types plus `switch` patterns for closed alternatives,
  `Optional` only as a return type, no nulls across module APIs where avoidable.
- Constructor injection only (field injection fails the architecture tests). No static mutable state.
- Compiler warnings are errors (`-Xlint:all -Werror`). Do not suppress a warning without a comment saying why.
- Layout: 4 spaces, no tabs, lines at most 120 characters, final newline, no trailing whitespace, no star imports
  (style checks in profile `quality`). Bug-pattern analysis must be clean; any exclusion needs a comment explaining why.
- Public API of a module (root package) has Javadoc explaining the contract; internal packages stay small and
  unexported. `package-info.java` of each module states purpose and allowed dependencies.
- Logging through the logging framework only, never standard streams. Never log secrets, passwords, tokens or
  personal data. Error responses never reveal internals.
- Tests: `*Test` unit tests (fast, no containers), `*IT` integration tests (failsafe, containers), architecture
  tests tagged `architecture`. Names describe behaviour. Tests run in UTC.
- Test data uses only generic names: tenants `tenant-a`, `tenant-b`; users `user-a`, `admin-a`; email addresses
  `user-a@example.test`. No real names, addresses or domains.
- Dependencies: versions only in the parent POM (Java) or pinned in `package.json` with the committed lock file
  (frontend). A new dependency needs a reason in the pull request. Fixable
  high/critical vulnerabilities in managed versions are overridden in the parent POM with a comment.
- Database: forward-only versioned migrations, backward compatible (expand/contract), one source of truth
  (ADR-0008); tenant-scoped tables get `tenant_id` and row level security.
- Frontend: TypeScript strict; presentation and interaction only (no business rules, no permission decisions);
  literal colours and sizes only in `src/styles/tokens.css`; the generated API client is never edited by hand;
  browser logging goes through `src/lib/log.ts`.
- Docs and comments: explain why, not what. No vendor, product, company or personal names in prose, comments,
  test data or ADRs (only the core stack may be named: Java, Spring, PostgreSQL, Next.js, Redis, Maven); say
  "ERP system" or "external system". Literal identifiers that build and infrastructure files cannot avoid
  (dependency and plugin coordinates, image references, CI action references) stay inside those files.
- AI-assistant files and local assistant settings are never committed (they are listed in `.gitignore`).

## Where things are

| Need | Look at |
|------|---------|
| Decisions | [adr/](adr/README.md) |
| Module graph | [modules.md](modules.md) |
| Authentication spike and Sprint 3 security stories | [spikes/d2-authentication-provider-spike.md](spikes/d2-authentication-provider-spike.md) |
| Local services | [../infra/local/README.md](../infra/local/README.md) |
| Migration convention | [adr/0008-database-migrations-single-source.md](adr/0008-database-migrations-single-source.md), [adr/0009-migration-framework-and-database-roles.md](adr/0009-migration-framework-and-database-roles.md), `scripts/sync-migrations.ps1` |
| API contract (source of truth) | `platform-api-contract/src/main/resources/openapi/platform-api-v1.json` |
