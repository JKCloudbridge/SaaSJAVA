# ADR-0084: The record store: one table, the values as JSON, typed copies reserved for indexes

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S14
- **Related:** [ADR-0002](0002-hybrid-data-store.md), [ADR-0003](0003-rls-defence-in-depth.md), [ADR-0010](0010-base-schema-conventions.md), [ADR-0015](0015-row-level-security-implementation.md), [ADR-0082](0082-storage-benchmark-specification-gate-g1.md), [ADR-0083](0083-storage-benchmark-result-gate-g1.md)

## Context

The gate (G1, ADR-0082 and ADR-0083) passed for one shape: every record of every object in one table, the values as a JSON object, and the fields that are flagged "indexed" copied into plain typed columns that carry the indexes.
The decisive finding was that with forced row level security PostgreSQL will not use an index on the JSON field operator, on the containment operator or on decimal comparisons (none is leakproof), so an index on the JSON itself is useless to
a tenant user. This ADR records the table that was built from that proof (migration `V036`).

## Decision

**Table `object_record`**, tenant-scoped (ADR-0015):

| Column | Meaning |
|--------|---------|
| `id` | the identifier (ADR-0085) |
| `tenant_id` | the organization; defaulted from the session, never from a request; forced row level security |
| `object_api_name` | which object, an API name from the metadata catalogue; never changes |
| `record_type` | the API name of the record type, or null (checking it is Sprint 15) |
| `owner_id` | the user who owns the record; the creator for now; record-level security (Sprint 17) reads it |
| `payload` | the values, one JSON object keyed by field API name; a field without a value has no key |
| `s_text_1..4`, `s_num_1..2`, `s_date_1..2`, `s_ref_1..2`, `s_bool_1` | **slots**: typed copies of indexed fields; empty and unused until Sprint 16 |
| base columns and soft delete | `version`, `created_at/by`, `updated_at/by`, `deleted_at/by` (ADR-0010, ADR-0086, ADR-0087) |

- **What lives here.** Every organization-made object and the standard objects that no platform module owns (`managedBy` empty: Account, Contact, Opportunity, Case). A standard object that a platform module owns (a user, a profile, an access
  policy, a role, a licence type, a record type) keeps its rows in that module and is **not served** by the record store: the gate answers "not found" for it. This also closes the question left open in Sprint 13: rules and formulas on platform-owned
  objects stay out until a module decides to expose them.
- **Indexes (from the benchmark):** `(tenant, object, id)` for live rows (the list and every later query), `(tenant, object, id)` for the recycle bin, `(tenant, deleted_at)` for the clean-up. All partial, all tenant first. The indexes on slots are
  created by the index policy of Sprint 16, one per field flagged "indexed" (each costs a write, so none is made before it is asked for). Fill factor 90 leaves room on a page for updates that change no indexed column.
- **Why the slots are in V036 although nothing uses them.** The proof that passed the gate had them; adding nullable columns later would be a legal expand migration, but the table that was measured is the table that ships, and an unused nullable
  column costs a bit of the null map. Sprint 16 decides which field gets which slot; the limit per type (4 text, 2 number, 2 date, 2 reference, 1 boolean) is then a limit on indexed fields per object, which Sprint 16 may widen by adding columns.
  A decimal indexed in a floating point slot is compared again exactly against the payload.
- **Guards in the database.** The row guard (version raised by exactly one, base columns immutable, a record in the bin read-only), the tenant guard, and a new identity guard (`platform_record_identity_guard`): the object of a record never changes.
  Checks: the object and record-type names have the shape of an API name; the payload is a JSON object of at most 512 KiB (the application allows 256 KiB by default).
- **JDBC, not JPA.** The records are dynamic: the table has no entity. `JsonbRecordStorage` is the only class that names the table; it uses parameterized statements only (ADR-0088).

## Consequences

- Reading one record, filtering and sorting on an indexed field, joining and updating are a few milliseconds at 2.2 million records on the benchmark machine (ADR-0083); scanning a whole object on a field without an index is not, and Sprint 16 must
  refuse or time-box such filters on large objects.
- Every write goes through `StorageStrategy`; a store with typed tables or a relational table for one high-value object can be added behind it (ADR-0088).
- A record's values are typed text: they never enter a log, an audit record, an event or an error (ADR-0089).

## Alternatives considered

- **Indexes on JSON expressions or a containment index:** measured as unusable under forced row level security (diagnostic in ADR-0083). Rejected.
- **One table per object:** the cost of schema changes per tenant action, and the metadata engine already describes the shape. Rejected by ADR-0002.
- **Typed columns only, no JSON:** every field type change would be a migration. Rejected.
