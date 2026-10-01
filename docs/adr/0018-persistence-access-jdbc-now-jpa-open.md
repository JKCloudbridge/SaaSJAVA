# ADR-0018: Persistence access: plain JDBC for platform tables now, JPA adoption left open

- **Status:** Accepted for the code as it stands (Sprints 1 and 2). Whether to adopt JPA for stable entities is **open** and closes
  no later than Sprint 14 (gate G1), or earlier through a new ADR that supersedes this one.
- **Date:** 2026-10-01
- **Sprint:** S2 follow-up (written while Sprint 3 had started)
- **Relates to:** [ADR-0002](0002-hybrid-data-store.md), [ADR-0010](0010-base-schema-conventions.md),
  [ADR-0015](0015-row-level-security-implementation.md)

## Context

The architecture notes (the baseline in note 0 and the data notes) list Spring Data JPA and Hibernate in the stack, with the
reminder that "Hibernate should not own everything": stable platform entities (users, tenants, licences) suit an object mapper,
while the tenant-defined record store needs controlled, metadata-driven SQL. The delivery plan's traceability table assigns the
decision "JPA for stable entities, JDBC for dynamic records" to Sprint 14.

What was actually built in Sprints 1 and 2 does not use JPA at all. The tenant, the outbox and the idempotency markers are
read and written with Spring's JDBC client (explicit SQL, immutable Java records for results), the migration tool is the
versioned-SQL tool of ADR-0009, and there is no persistence-framework dependency in the build. That was a choice made while
implementing, and no record said so. This ADR records it and says what is still open.

## Decision

1. **Today, platform tables are accessed with the JDBC client and explicit SQL.** Results are mapped to records by small row
   mappers in the owning module's internal package. No JPA or Hibernate dependency exists in the build.
2. **Why this fits what the platform already enforces:**
   - The base conventions (ADR-0010) are enforced by a trigger that refuses an update unless the version increases by exactly
     one. Explicit SQL makes the version-guarded update (`where id = ? and version = ?`) visible in every write and impossible
     to forget silently.
   - The tenant is put into the database session when a transaction begins (ADR-0015). That works with any access technology,
     but with explicit SQL a reviewer can see every statement that touches a tenant-scoped table, and nothing is fetched lazily
     outside a transaction where the tenant is no longer set (such a query would see no rows).
   - Queries on tenant tables are few and simple so far; an object mapper would add generated SQL, caching and lazy loading that
     must each be proven compatible with row level security before they are trusted.
3. **The record store stays outside any object mapper regardless** (ADR-0002, notes 4): tenant-defined objects are queried
   through the controlled query model of Sprint 16, with no arbitrary SQL from metadata.
4. **No decision yet to adopt JPA** for stable entities (users and credentials, licences, security metadata). It is open.
   Neither choice is wrong; the cost of choosing late is that some modules will already have JDBC repositories.

## If JPA is adopted later (conditions, so the decision is not made by accident)

A new ADR supersedes this one and shows, with tests, that the mapped entities:
- use an optimistic-lock version column compatible with the row guard (the version must advance by exactly one);
- run only inside transactions that begin after the tenant context is open, with no lazy loading or second-level cache
  that could serve another tenant's data;
- pass the same leak harness and schema scanner (`docs/tenant-isolation-testing.md`) as the JDBC tables;
- do not touch the record store or the outbox tables.
Until then a sprint that needs a new table uses the JDBC client, following the patterns in the tenant module
(`TenantRepository`, `OutboxStore`).

## Consequences

- Repositories are a little more code than mapped entities, and the writer of a module must write the SQL.
- The behaviour of every statement against tenant tables is reviewable and testable with the existing harness.
- Sprint 3 (identity) and later sprints can add tables without a new persistence framework; if a team prefers JPA for a module,
  the conditions above apply.
- The notes' mention of JPA is a stack-level intention, not a rule broken here: the decision is deferred, not rejected.

## Alternatives considered

- **Adopt JPA from the start for all platform entities:** conventional and less code, but its interplay with row level security,
  the version trigger and transaction-start tenant setting had to be proven first, and Sprints 1 and 2 had no entity that needed it.
- **Reject JPA permanently:** premature; stable entities with many relations (security metadata, licences) may benefit from it.
- **Hand-rolled mapping utilities or another query library:** more to maintain than the JDBC client the framework already
  provides.

## References

- `platform-app/src/main/java/app/platform/tenant/internal/TenantRepository.java`,
  `platform-app/src/main/java/app/platform/outbox/internal/OutboxStore.java`
- Delivery plan traceability: "JPA for stable entities, JDBC for dynamic records" (Sprint 14);
  architecture notes 0 (baseline) and 4 (sections on the generic record table and "Hibernate shouldn't own everything").
