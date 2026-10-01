# ADR-0009: Migration framework, database roles and start-up

- **Status:** Accepted
- **Date:** 2026-10-01
- **Sprint:** S1
- **Builds on:** [ADR-0008](0008-database-migrations-single-source.md) (where migration files live and how they are named)

## Context

ADR-0008 fixed the files and the naming but left the tool open. The platform needs a versioned SQL migration tool
that Spring Boot integrates, that accepts `V<nnn>__<snake_case>.sql`, that never rewrites history, and that can run
as a database role other than the one the running application uses. The second point matters because of
[ADR-0003](0003-rls-defence-in-depth.md): row level security protects tenants only if the application's role neither
owns the tables nor can change them, and Sprint 2 introduces exactly that role.

## Decision

1. **Tool.** The versioned-SQL migration tool that ships with Spring Boot's auto-configuration (its coordinates are in
   `platform-app/pom.xml`; versions are managed by the Spring Boot BOM). Reasons: it reads the file names ADR-0008
   already uses; migrations are plain SQL that is reviewed in pull requests; it records a checksum per migration and
   refuses to start when an applied migration was edited; it applies each migration in a transaction (PostgreSQL DDL is
   transactional, so a failed migration leaves nothing half applied); it serializes concurrent start-ups with a database
   lock.
2. **Forward-only.** No undo scripts, no clean, no baseline, no out-of-order application. A mistake is corrected by a new
   migration. Configured in `application.yml` and asserted by `DatabaseRolesIT.theMigrationToolIsConfiguredForwardOnly`.
3. **Two roles, two sets of credentials.**
   - *Owner role*: runs migrations and owns every object. Variables `PLATFORM_DB_OWNER_USER` / `PLATFORM_DB_OWNER_PASSWORD`
     (the migration tool's own connection settings in `application.yml`). Used only while migrating.
   - *Application role*: the runtime connection pool. Variables `PLATFORM_DB_APP_USER` / `PLATFORM_DB_APP_PASSWORD`
     (the connection pool settings). It must not be a superuser, must not bypass row level security and must not own tables.
   - Both use `PLATFORM_DB_URL`. In the **`local` profile** both default to the local database user from
     `infra/local/.env` (`POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`, `POSTGRES_PORT`), because the separate
     application role is created by Sprint 2's manual migration. In **`prod`** all five variables are mandatory and the
     application stops with a clear message when one is missing. The integration tests always run the application as a
     separate, unprivileged role, so a regression that needs owner rights fails the build.
4. **Rules enforced by tests** (they fail the build):
   - `MigrationNamingTest`: `V<nnn>__<snake_case>.sql` and `M<nnn>__<snake_case>.sql`; three digits; numbers unique and
     consecutive from 001; no other files; LF line endings (so checksums are identical on Windows and Linux) and a final
     newline. It also feeds the rule deliberately bad names, so it cannot pass vacuously.
   - `MigrationIT`: an empty real PostgreSQL is migrated to the current version in order; migrating again changes nothing; an
     edited migration is refused; a failing migration leaves no partial change.
   - Never edit a released migration (ADR-0008); backward compatibility (expand/contract) is a review rule, because it
     cannot be decided by a tool.
5. **When migrations run.** At application start-up, through the owner connection. This keeps local, test and early
   environments simple. Sprint 33 may move the step into a separate deployment job that runs before the rollout
   (the application is told not to migrate itself); nothing in the migrations depends on either choice.
6. **UTC.** The application pins its default time zone to UTC at start-up. Reason: Sprint 0 and Sprint 1 both met
   machines whose JVM reports a legacy zone alias that PostgreSQL 18 rejects at connect time. The container image and the
   tests also run in UTC.

## Consequences

- One review point for schema changes; the history is reproducible on any empty database.
- Starting the application against a database whose history differs from the files stops with a checksum error rather
  than guessing.
- Running as owner at start-up means the owner credentials are present in the application's environment. That is
  accepted for now and removed by the separate-job option above.
- The application role has no schema privileges, so a migration that creates a table must also grant what the
  application needs (Sprint 2 introduces the grant pattern together with the role).

## Alternatives considered

- **Changelog tools with XML or YAML change sets and generated rollbacks:** more machinery, hides the SQL that runs, and
  rollbacks are rarely trustworthy; forward-only with expand/contract is the project's policy.
- **ORM schema auto-update:** not reviewable and not reproducible.
- **A hand-written runner:** re-implements checksums, locking and ordering for no gain.

## References

- `platform-app/src/main/resources/application.yml`, `application-local.yml`, `application-prod.yml`
- `platform-app/src/main/resources/db/migration/`
- `platform-app/src/test/java/app/platform/database/` (`MigrationNamingTest`, `MigrationIT`, `DatabaseRolesIT`)
- `infra/local/.env.example`, `scripts/sync-migrations.ps1`
