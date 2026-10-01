# ADR-0002: Hybrid data store: relational platform data, JSONB tenant records

- **Status:** **Proposed.** Not final until hard gate **G1** (storage benchmark, Sprint 14) passes.
- **Date:** 2026-10-01
- **Sprint:** S0 (provisional); closes in S14

## Context

Tenants define their own objects and fields at runtime, so the table structure of tenant business
data is not known at build time. Platform control data (tenants, identity, metadata definitions, security,
audit) has a stable, known structure. Storage for tenant records must give correct tenant isolation,
acceptable query performance at large sizes (millions of records per object per tenant), concurrency control
and bounded operational cost, without arbitrary SQL or schema changes per tenant.

## Decision (provisional)

1. **Platform control data** is relational with a normal schema, accessed with JPA for stable entities.
2. **Tenant business records** are stored in a shared table (`object_record`) with a **JSONB payload**,
   tenant and object identifiers, owner, audit columns, soft delete and an optimistic-concurrency version,
   protected by row level security (ADR-0003) and tenant-scoped indexes. Accessed with JDBC through a
   `StorageStrategy` abstraction, never directly by feature code.
3. `StorageStrategy` exists so that **a fallback can be adopted without rewriting callers**:
   typed projection tables for hot fields, or a fully relational store for high-value objects.
4. **Gate G1 decides.** Before any benchmark runs, Sprint 14 first writes the benchmark specification with
   pass/fail thresholds and commits it as an ADR draft. The delivery plan's starting thresholds are:
   p95 single-record read <= 20 ms; p95 indexed filter+sort+page <= 150 ms at 1M records per object and
   <= 300 ms at 10M; p95 non-indexed filter on 1M records <= 1 s without exhausting the connection pool;
   row level security overhead <= 10% p95; bulk upsert >= 5,000 records/s per worker; no tenant's load
   degrades another tenant's p95 by more than 25%. They are confirmed or changed in the specification
   **before** running, not after.
5. **PASS** continues with the JSONB design. **FAIL** stops work, adopts a documented fallback behind
   `StorageStrategy`, re-runs G1 and records a new ADR. No Sprint 15+ work starts before G1 passes.

## Consequences

- Fast delivery of the metadata engine without per-tenant DDL.
- Query performance on JSONB is the principal risk; managed indexes and the fallback option mitigate it.
- Sprints 15 and 16 take the G1 result as an input to their design.
- Nothing in S0 to S13 may assume JSONB storage for tenant records beyond defining the `StorageStrategy`
  boundary in S14.

## Alternatives considered

- **Physical table per tenant object (dynamic DDL):** strong typing and indexing, but schema churn,
  migration and connection/plan-cache cost grow with tenants x objects.
- **Entity-attribute-value rows:** flexible but poor query performance and complex SQL.
- **Document database:** weaker transactional guarantees with the relational platform data, and a second
  operational system.

## References

- Delivery plan, Sprint S14 (gate G1); traceability note 4.
- Evidence so far: S0 only confirmed that a real PostgreSQL 18 container supports row level security in the
  integration-test setup (`PostgresContainerIT`). **No performance evidence exists yet.**
