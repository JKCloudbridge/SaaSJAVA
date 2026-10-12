# ADR-0088: The storage strategy

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S14
- **Related:** [ADR-0002](0002-hybrid-data-store.md), [ADR-0082](0082-storage-benchmark-specification-gate-g1.md), [ADR-0084](0084-the-record-store.md)

## Context

The plan says the platform uses a hybrid store and that "the data engine will abstract storage strategy from the rest of the platform". The benchmark gate named its fallbacks (typed tables for hot fields, a relational table for a high-value object,
partitioning). None of them may need a change outside the data module.

## Decision

Two public interfaces in the root of the `data` module:

- **`Records`**: what the platform may do with records as the signed-in member: create, read, change with a version, delete, restore, remove for good, list and list the recycle bin. It is the only thing other modules and the endpoints call. It passes the
  gate (the member, the object, the permission) and writes the audit record and the event. It knows nothing about a table.
- **`StorageStrategy`**: where records are kept. Below the rules: no permission, no field-name check, no value check, no event, no audit. Methods take an object API name, field names and identifiers as **parameters** and answer for the organization of
  the tenant context, filtering by it themselves (and the database filters again).

`JsonbRecordStorage` (internal) is the one implementation. It is the only class that names `object_record`. Its statements are constants; a name from outside is only ever a parameter (a test shows a hostile object or field name is data, and the database
itself refuses an object name that is not shaped like an API name).

**How a second store plugs in.** A strategy that serves some objects (a typed table for Case, say) is a second bean; a router in front of `Records` picks the strategy by object (the router does not exist yet and is added with the first second
strategy; today there is one bean). The rest of the platform sees no difference. A store that moves records between strategies needs an export through `StorageStrategy.list` and an insert in the other: this is not built.

## Consequences

- A change of storage is a change inside `data.internal` and a migration, plus the benchmark (the harness in `benchmark/` is the regression suite and runs against the strategy in Sprint 16).
- `StorageStrategy` is a public contract that other modules could call; the architecture rule is that they do not: the permission check lives in `Records`. A test in a later sprint may enforce it with an architecture rule.
