# ADR-0010: Base schema conventions: identifiers, audit columns, soft delete, versions

- **Status:** Accepted
- **Date:** 2026-10-01
- **Sprint:** S1

## Context

Every later table (tenants, users, metadata, records) needs the same bookkeeping: a stable identifier, who created
and changed it and when, a way to delete without losing history, and protection against two people overwriting each
other. If each sprint invents its own version, the platform gets inconsistent keys, missing audit data and lost
updates. A convention that is only written down is forgotten; this one is enforced by the database and by a test.

## Decision

Every platform table has these columns and this trigger. (Tenant-scoped tables additionally get `tenant_id` and row
level security in Sprint 2; that extends this convention, it does not replace it.)

| Column | Type | Rule |
|--------|------|------|
| `id` | `uuid` primary key | `default uuidv7()`: a time-ordered version 7 UUID. Created in SQL by the database, or in Java with `Uuids.v7()` when the identifier is needed before the insert. The primary key is exactly `(id)`. |
| `version` | `bigint not null default 0` | Optimistic concurrency counter. Every update sets `version = version + 1` and is guarded by `where id = ? and version = ?`; zero rows updated means someone else changed the row (the API answers `CONCURRENT_MODIFICATION`). |
| `created_at`, `updated_at` | `timestamptz not null` | Set by the database clock, in UTC. Never taken from the caller. |
| `created_by`, `updated_by` | `uuid not null` | The actor, supplied by the application (`ActorId`). Work done by the platform itself uses `ActorId.SYSTEM`, the all-zero identifier. No foreign key: identities belong to another module. |
| `deleted_at`, `deleted_by` | `timestamptz`, `uuid` | Soft delete. `deleted_at is null` means the row is live. Both are set together, by an update that also bumps the version. |

The trigger `<table>_row_guard` (`before insert or update`, for each row, function `platform_row_guard()`, created by
migration `V001`) makes the rules binding on every code path, including raw SQL and future bulk operations:

- on insert it sets the timestamps, resets `version` to 0 and clears the deletion columns;
- on update it refuses a change to `id`, `created_at` or `created_by`, refuses any update whose `version` is not exactly
  the old version plus one, sets `updated_at`, refuses to edit a deleted row until it is restored, requires `deleted_by`
  when deleting, takes `deleted_at` from the database clock, and clears `deleted_by` on restore;
- its error messages name the table, never a value (logs and clients must not receive row data), with SQLSTATE
  `23514` (check violation);
- a permanent delete is a plain `DELETE` and is allowed (recycle bin and retention rules come with Sprint 14).

**Unique indexes must be partial** (`where deleted_at is null`) so a soft-deleted row does not block its key.

**Enforcement.** `SchemaConventionsIT` proves each rule on a real PostgreSQL and runs a scanner over the real `public`
schema: a table without the columns (or with the wrong types), without the identifier default, without the trigger,
with a non-partial unique index or with a different primary key fails the integration build. The scanner is proven to
catch each kind of deviation on deliberately bad tables. Only the migration tool's own history table is exempt.

**Table template** (copy into a migration):

```sql
create table example (
    id          uuid        primary key default uuidv7(),
    -- business columns here
    version     bigint      not null default 0,
    created_at  timestamptz not null default now(),
    created_by  uuid        not null,
    updated_at  timestamptz not null default now(),
    updated_by  uuid        not null,
    deleted_at  timestamptz,
    deleted_by  uuid
);
create trigger example_row_guard before insert or update on example
    for each row execute function platform_row_guard();
```

## Consequences

- Index locality: time-ordered keys append to the right of the B-tree, which keeps inserts cheap and indexes compact,
  unlike random identifiers. Identifiers reveal creation time (to the millisecond); that is accepted, and they are not
  secrets.
- Every update statement must carry the version; JPA entities use `@Version`, JDBC code uses the guarded update. The
  trigger turns a forgotten bump into an immediate error in tests.
- A manual data correction must also bump the version.
- Soft delete means queries must filter `deleted_at is null` (a repository concern; row level security for tenants
  arrives in Sprint 2).
- The record store of Sprint 14 may store tenant-defined objects in one table with these columns (ADR-0002 is still
  open until gate G1).
- Not covered here and decided later: audit log of data changes (Sprint 9), retention and permanent delete policy
  (Sprints 14 and 34).

## Alternatives considered

- **Random version 4 identifiers:** unordered, fragment indexes. Sequential numbers: guessable, collide between
  environments, awkward to merge.
- **Application-only conventions (base entity class):** do not cover SQL, bulk operations or the JDBC record store.
- **History tables for every row change:** the audit module (Sprint 9) owns change history; these columns record
  only the latest actor and time.

## References

- `platform-app/src/main/resources/db/migration/V001__base_schema_conventions.sql`
- `platform-shared-kernel/src/main/java/app/platform/sharedkernel/Uuids.java`, `ActorId.java`
- `platform-app/src/test/java/app/platform/database/SchemaConventionsIT.java`, `SchemaConventions.java`
