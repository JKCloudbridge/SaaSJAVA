# ADR-0004: Maven build, wrapper, BOM and enforcer rules

- **Status:** Accepted
- **Date:** 2026-10-01
- **Decision IDs:** D1
- **Sprint:** S0

## Context

The build must be conventional, predictable, reproducible on any machine and in CI, strongly supported by
Spring Boot, and able to enforce rules (Java version, dependency hygiene) rather than document them.

## Decision

1. **Maven**, run only through the **Maven wrapper** (`./mvnw`, pinned Maven 3.9.16), so every developer and CI
   use the same version.
2. **Versions:** Java 25 (LTS), Spring Boot 4.1.1 (latest stable release that builds and passes all tests on
   Java 25; verified in Sprint 0), Spring Modulith 2.1.1 (not managed by the Boot BOM, imported separately).
3. **Parent POM is the single place for versions.** It inherits the Spring Boot starter parent (plugin
   management, UTF-8, `-parameters`), imports the Spring Modulith BOM, pins build plugins, and declares
   the platform's own modules. Child POMs declare no versions.
4. **Three Maven modules** to start (logical modules do not equal Maven modules, ADR-0001):
   - `platform-shared-kernel`: identifiers and cross-cutting interfaces (for example the secrets store).
   - `platform-api-contract`: the published HTTP contract (paths now; envelope, errors and the OpenAPI
     source in Sprint 1). Depends on nothing in the platform.
   - `platform-app`: the Spring Boot application hosting all logical modules.
5. **Enforcer rules (fail the build):** Java 25 or newer; Maven 3.9.9 or newer; no unpinned/latest/snapshot
   plugins; no duplicate dependency versions; dependency convergence; banned legacy libraries
   (log4j 1/core, `javax.*` servlet/persistence/validation/annotation, the log4j2 starter).
6. **Compiler:** `-Xlint:all` with warnings as errors.
7. **Profiles:** `quality` (style checks and bug-pattern analysis), `integration-only` (run only the failsafe integration tests).
8. **Test conventions:** `*Test` = unit/architecture (surefire; architecture tests carry the JUnit tag
   `architecture`); `*IT` = integration (failsafe, real PostgreSQL in a container). Tests run in UTC.
9. **Security overrides:** when the container/dependency scan reports a fixable high or critical issue in a
   version managed by the Boot BOM, the fixed version is set as a property in the parent POM with a comment,
   and removed when the BOM catches up. Two exist since Sprint 0 (embedded web server and JSON library).

## Consequences

- One command builds and tests everything: `./mvnw verify`.
- Upgrading Spring Boot is a one-line change plus a test run.
- The wrapper downloads Maven on first use; offline machines need it cached.

## Alternatives considered

- **Gradle:** powerful but less predictable for a team learning the stack; Maven's convention-over-
  configuration was preferred (decision D1).
- **Single Maven module:** simpler, but gives no physical boundary for the API contract and shared kernel.
- **A Maven module per logical module:** see ADR-0001.

## References

- `platform/pom.xml`, `platform/.mvn/wrapper/maven-wrapper.properties`, `platform/config/`.
