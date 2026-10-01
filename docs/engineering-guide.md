# Engineering guide

Multi-tenant, metadata-driven application platform. Modular monolith. This is what a contributor needs inside
this repository: commands, module rules, standing architecture rules, the Definition of Done and coding standards.

## Stack and versions

Java 25 (LTS), Spring Boot 4.1.1, Spring Security 7.1, Spring Modulith 2.1.1, PostgreSQL 18, Redis 8,
S3-compatible object storage, Maven 3.9.16 via the wrapper. Frontend (Sprint 1 onward): Next.js, React, TypeScript.

## Commands

Run from the repository root. JDK 25 must be the active Java (`java -version`).

| Task | Command |
|------|---------|
| Everything (compile, unit, architecture, integration, package) | `./mvnw verify` |
| Fast loop, no containers | `./mvnw test` |
| Unit tests only | `./mvnw test -DexcludedGroups=architecture` |
| Architecture tests only | `./mvnw test -Dgroups=architecture` |
| Integration tests only (needs a container runtime) | `./mvnw verify -Pintegration-only` |
| Static analysis | `./mvnw -Pquality -DskipTests -DskipITs verify` |
| One module | `./mvnw -pl platform-app -am verify` |
| Run the app | `java -jar platform-app/target/platform-app-0.1.0-SNAPSHOT.jar` |
| Local services | `cd infra/local; ./init-env.ps1; docker compose up -d; ./verify.ps1` |
| Spike tests | `./mvnw -f spikes/d2-auth-provider/pom.xml test` |
| Container image | `./mvnw -DskipTests package` then `docker build -t platform-app:local platform-app` |
| Refresh the migrations mirror | `./scripts/sync-migrations.ps1` (check: add `-Check`) |

CI (`.github/workflows/ci.yml`) runs the same stages: build, unit, architecture, integration, static analysis,
dependency vulnerability scan, container image build and scan. A change is done only when all pass.

## Modules

Logical modules are packages under `app.platform` in `platform-app` (details and allowed dependencies in
[modules.md](modules.md); decision in [adr/0001-modular-monolith.md](adr/0001-modular-monolith.md)):

`tenant`, `identity`, `licensing`, `security`, `metadata`, `data`, `application`, `workflow`, `approval`,
`notification`, `integration`, `audit`, `platformadmin` (platform admin), `sharedkernel` (separate Maven module).

Maven modules: `platform-app`, `platform-shared-kernel`, `platform-api-contract` (package `app.platformapi`,
depends on nothing in the platform).

Rules enforced by tests (they fail the build):
- A module may use only another module's root package (its public API); sub-packages are internal.
- A module may depend only on the modules named in its `@ApplicationModule(allowedDependencies = ...)`.
- No cycles. Only `integration` may reference `app.platform.integration.adapter..`.
- Adding a dependency edge = edit that module's `package-info.java` and `docs/modules.md` in the same change.
- Never read or write another module's tables.

## Standing architecture rules

- The backend is authoritative: frontend validation and permission checks are UX only.
- The tenant ID is never trusted from the browser; derive it from authenticated identity, membership and host.
- Core modules never reference a specific external system; only `integration` knows adapters.
- No arbitrary SQL or executable code from tenant metadata.
- Licence, permission and feature entitlement are three separate mechanisms.
- No secrets in code, configuration, logs or metadata tables (secrets interface in `sharedkernel`).
- Tenant data is protected by application filtering **and** PostgreSQL row level security (ADR-0003).

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
- Dependencies: versions only in the parent POM. A new dependency needs a reason in the pull request. Fixable
  high/critical vulnerabilities in managed versions are overridden in the parent POM with a comment.
- Database: forward-only versioned migrations, backward compatible (expand/contract), one source of truth
  (ADR-0008); tenant-scoped tables get `tenant_id` and row level security.
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
| Migration convention | [adr/0008-database-migrations-single-source.md](adr/0008-database-migrations-single-source.md), `scripts/sync-migrations.ps1` |
