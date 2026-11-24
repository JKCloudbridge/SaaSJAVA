# ADR-0288: Migrations as a job, the compatibility rule, and rollback (S33)

- **Status:** Accepted
- **Date:** 2026-11-24
- **Sprint:** S33
- **Related:** [ADR-0003](0003-rls-defence-in-depth.md), [ADR-0008](0008-database-migrations-single-source.md), [ADR-0009](0009-migration-framework-and-database-roles.md), [ADR-0010](0010-base-schema-conventions.md), [ADR-0289](0289-zero-downtime-releases-the-drain-and-the-threshold-of-the-proof.md)

## Context

ADR-0009 says the owner role migrates and the application role has no schema rights, but until now whichever instance started
first migrated, with the owner's password in its environment. Several instances starting together, and a release that serves
while it migrates, need the migration to be a step of its own.

## Decision

1. **The migration job is the application image's `migrate` command** (`MigrationJob`): the same jar as the release it migrates
   for, so the migration files and the library cannot drift from the application. It needs three values (`PLATFORM_DB_URL`,
   `PLATFORM_DB_OWNER_USER`, `PLATFORM_DB_OWNER_PASSWORD`), uses the settings of `application.yml` (forward only, no clean, no
   out-of-order, no placeholders), logs two lines (started, finished with the count and the version), exits 0, 1 (failed) or 2
   (a value missing), and never prints a password, an address or the library's message: a failure is named by exception classes,
   the SQL state and the migration file; the full text is in the database server's log.
2. **Two jobs at once are safe.** The library holds a database lock for the whole migration; the second waits, finds nothing to do
   and exits 0 (`MigrationJobIT` starts two at the same moment). Each migration is one transaction, so nothing is half applied.
3. **The application starts without the right to migrate** in a deployment: `PLATFORM_MIGRATE_ON_START=false` switches the
   library off (`prod` profile only; the default stays true, so `local`, the tests and the CI smoke are unchanged) and the
   deployment does not give the application the owner's credentials. Instead the application **checks the schema**: a bean factory
   post processor (`SchemaCheck`), before any other bean, asks `platform_schema_version()` and compares it with the highest
   `V<nnn>` file of the release. Behind: the start is refused with one sentence naming the two numbers and the way out. Equal:
   starts. Ahead: starts and says so, because a newer release migrated and every migration is backward compatible (the rollback
   case).
4. **V097 is the one migration this decision needs.** The application role cannot read the migration history (M001, deliberately),
   so it cannot ask the library. `platform_schema_version()` is a `security definer` function returning the highest applied
   version as a whole number and nothing else; it is callable by everyone, which PostgreSQL's default gives a new function, and
   that is intended (a version number is no secret). Expand only: the release before still runs.
5. **The compatibility rule (expand and contract), written as a deploy rule.** While a release can still be rolled back to the
   one before it, a migration only adds: a table, a nullable column, a constraint `not valid` then validated, an index
   concurrently, a function. A column or table the old release reads is removed only in a later release than the one that stopped
   reading it. Because the old release must run on the new schema, **the way back is the previous image, never an undo of the
   database.** The library ignores migrations newer than the release (its default for future migrations), so an older image
   starts on a newer schema; the rehearsal proves it with the real previous image (`rollback.ps1`).
6. **The long-lock check.** A migration after the reviewed baseline (V096) that holds a heavy lock on a table that already has data
   or breaks the release before it fails a unit test (`PendingMigrationsAreSafeTest` with `MigrationRules`): a column type
   change, `set not null`, a volatile default, an update or delete with no `where`, `create index` without `concurrently`, a
   foreign key or check that is not `not valid`, `drop table`, `drop column`, a rename, `lock table`, `truncate`, `vacuum full`,
   `cluster`, a plain `reindex`. A table the same file creates is empty and exempt. A file with a real reason carries
   `-- long-lock-reviewed: <reason of at least ten characters>`, which puts the reason where the next person reads it.
7. **M001 and M002 stay manual**, run once per environment by the owner with the application role's password set out of band;
   `runbooks/database.md` has the procedure and a check that the role has no schema rights (`SchemaCheckIT` proves it).

## What rollback cannot mean

Not an undo of a migration. Not a return to a release from before the migration job existed unless that release is given its own
old environment shape (the owner's password, key files): the first rollback target after this sprint is a release that already has
the job. A rollback is also not possible once a contract step has run; that step belongs to a later release and its runbook says
so (`runbooks/rollback.md`).
