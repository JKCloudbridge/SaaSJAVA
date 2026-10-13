# ADR-0105: Filters and orders without an index on large objects

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S16
- **Related:** [ADR-0083](0083-storage-benchmark-result-gate-g1.md) (condition 4), [ADR-0100](0100-the-query-model-and-its-stages.md), [ADR-0101](0101-the-index-policy-and-the-slots.md), [ADR-0108](0108-limits-per-organization.md)

## Context

A filter on a field without an index reads every record of the object: 0.6 to 1.2 s at 1.2 million records on the benchmark machine, and growing with size. Ten million records were not measured. Sprint 14 named the choice: refuse or time-box. This sprint decides.

## Decision

1. **A question is a scan** when it has no indexed way to narrow the records or its order is not indexed. A filter is indexed when an `AND` has at least one indexed condition and an `OR` has all of its conditions indexed; a condition is indexed when its field has a **complete** slot (or is the identifier or
   the creation time) and the comparison is one an index serves (`EQ GT GTE LT LTE BETWEEN IN IS_NOT_EMPTY`). `NE`, `IS_EMPTY`, `STARTS_WITH` and `CONTAINS` never are. An order is indexed when each key is in a complete slot or is the identifier or the creation time.
2. **A scan is allowed on an object with at most 20,000 live records** (`platform.data.query.unindexed-scan-limit`), decided by a capped count (at most 20,001 index entries are read), and **refused above that** with `VALIDATION_ERROR` at `query` that says what to do: which fields to ask an administrator to flag
   as indexed; "its index is not complete yet, try again in a few minutes" for a field that is flagged but not filled; or "add a condition on an indexed field" when the comparison itself cannot use an index. The statement time limit of the organization (ADR-0108) is the second line of defence.
3. **The refusal names only fields the member may read** (the fields were resolved against their permissions first, ADR-0100), and says nothing about how many records there are beyond "too many".
4. **System fields:** `id` and `createdAt` are indexed. `ownerId`, `createdById`, `updatedAt` and `updatedById` are not and follow the rule (the owner gets its index with record-level security, Sprint 17; the time of last change would end heap-only updates).
5. **A formula field is refused** as a filter or sort field with the reason (ADR-0095), whatever the size of the object.
6. **The count is not cached** in Sprint 16 (a capped count over the identifier index is a few milliseconds); the benchmark measures the guard itself.

## Consequences

- A missing index appears as an answer an administrator can act on, never as a slow request that nobody can explain.
- An organization with a small object never has to think about indexes; one with a large object must flag the fields it filters by, and the object manager shows the progress (`index-status`).
- Text search (`CONTAINS`) over a large object is not available until the search strategy of Sprint 17.
