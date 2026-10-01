# ADR-0008: Database migrations: one source of truth

- **Status:** Accepted
- **Date:** 2026-10-01
- **Sprint:** S0 (convention and tooling); the migration framework itself arrived in Sprint 1 ([ADR-0009](0009-migration-framework-and-database-roles.md))

## Context

Two requirements meet here. The application's migration tool needs migration files inside the repository so
that CI, containers and every developer apply exactly the same history. The project also keeps a human-facing
folder, `Shree Ganeshay Namah/Migrations/`, where every migration is listed with a zero-padded number and
where anything a person must run manually is written down clearly. Two editable copies would drift.

A quick test in Sprint 0 showed that the application's versioned-migration tooling does not accept names of
the form `001 create tenant table` (it requires a letter prefix and a double-underscore separator), so the
human-facing names cannot be the files the tool reads.

## Decision

1. **The repository is the only editable source.**
   - Automatic migrations (run by the application at start-up, forward-only):
     `platform-app/src/main/resources/db/migration/V<nnn>__<snake_case_name>.sql`
   - Manual migrations (need elevated rights or a human decision, for example creating database roles; never
     run by the application): `db/manual/M<nnn>__<snake_case_name>.sql`
2. **The `Migrations` folder is a generated, read-only mirror**, produced by `scripts/sync-migrations.ps1`:
   `V001__create_tenant_table.sql` appears as `001 create tenant table.sql`; manual scripts appear under
   `Migrations/Manual/`. Nobody edits the mirror.
3. `scripts/sync-migrations.ps1 -Check` reports a stale or hand-edited mirror and exits non-zero. It is run
   at the end of every sprint and whenever a migration changes. (It cannot run in CI because the mirror
   lives outside the repository.)
4. Rules for migrations themselves (enforced from Sprint 1 by a test that reads the folder):
   forward-only; never edit a migration that has been released (add a new one); backward compatible with the
   previous release (expand/contract); numbers are three digits, unique, and strictly increasing.
5. `Migrations/README.md` explains this to the reader and lists how to run manual scripts.

## Consequences

- A single place to review database history in a pull request; the human-facing view is always derivable.
- A manual step exists to refresh the mirror; the check mode makes forgetting it visible.
- Sprint 1 picks and wires the migration tool (the framework is Sprint 1 scope) using this convention and adds
  the naming test.

## Alternatives considered

- **Point the tool directly at the `Migrations` folder:** impossible with the required names, and the folder
  is outside the repository, so CI and containers could not see it.
- **Keep both folders manually in sync:** rejected, guaranteed drift.
- **Symbolic link or directory junction:** fragile on Windows and across clones.

## References

- `platform/scripts/sync-migrations.ps1`, `Shree Ganeshay Namah/Migrations/README.md`.
- Delivery plan Sprint S1 (migration framework), Definition of Done item 5.
