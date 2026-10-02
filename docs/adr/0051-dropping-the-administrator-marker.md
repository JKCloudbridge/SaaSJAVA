# ADR-0051: Dropping the administrator marker (contract migration)

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S8
- **Related:** [ADR-0043](0043-replacing-the-administrator-marker.md), [ADR-0009](0009-migration-framework-and-database-roles.md)

## Context

ADR-0043 replaced the Sprint 5 yes/no marker by abilities and promised a contract migration. Checking before dropping showed the marker on
`invitation` was **not** read by nothing: the invitation of a platform-provisioned first administrator set it and acceptance read it to
give the administrator profile.

## Decision (V027)

1. Code stops reading and writing both columns first; acceptance uses `invited_by_platform` (which already implied the administrator
   profile) and the profile the invitation names.
2. The migration turns every still-open, non-platform invitation that carried the marker and named no profile into one that names the
   administrator profile of its organization, so accepting it still makes the person an administrator.
3. The invitation and membership guard functions are replaced without the marker, then the index `membership_tenant_admin`, the constraint
   `membership_administrator_is_active` and the two columns are dropped. `founding_administrator` stays: a fact that grants nothing.
4. Forced row level security is switched off for the one statement and on again in the same transaction (as in V013, V018, V023).
5. Tested on a database migrated by a non-superuser owner with data (`MigrationIT`), and on the local database.
