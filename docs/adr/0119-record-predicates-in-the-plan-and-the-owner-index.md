# ADR-0119: Record predicates in the plan, and the index on the owner

- **Status:** Accepted
- **Date:** 2026-10-13
- **Sprint:** S17
- **Related:** [ADR-0100](0100-the-query-model-and-its-stages.md), [ADR-0101](0101-the-index-policy-and-the-slots.md), [ADR-0105](0105-filters-and-orders-without-an-index-on-large-objects.md), [ADR-0115](0115-the-record-access-model-and-how-it-reaches-a-statement.md), [ADR-0117](0117-explicit-sharing-and-who-may-share.md), [ADR-0082](0082-storage-benchmark-specification-gate-g1.md)

## Context

ADR-0115 decides that `security` describes what a member may reach and `data` turns the description into the one statement. The statement must stay fast on an object of millions of records (the benchmark of Sprint 14 and 16), under forced row level security, and must not build SQL from anything a person typed.

## Decision

1. **Two new plan nodes, `OwnedByAny` and `SharedWithAny`,** join `Group`, `Test` and `NoRecords` in `QueryPlan.Node`. `RecordVisibility` (the seam left in ADR-0100) turns the member's `RecordAccess` for the action into plan nodes: `Everything` adds nothing, `Nothing` becomes `NoRecords` (the statement returns no row and is not run against the table), `AnyOf` a group of `OR`, `OwnedBy` an `OwnedByAny`,
   `SharedWith` a `SharedWithAny`, and `Meets(rule, criterion)` the rule's condition run through the **same analyzer and planner as a query** (so a criteria rule is exactly as expressive and as safe as a filter, and uses the same slots and indexes). The result is added to the filter of every read with `and`.
2. **SQL stays constant fragments with parameters.** `JsonbQuerySql` writes the two nodes as `owner_id = any(cast(string_to_array(:owners, ',') as uuid[]))` and `id = any(array(select record_id from record_share where tenant_id = ... and grantee_id = any(...) and access_level >= :level and deleted_at is null))`. The sets are carried as one text parameter, not as a placeholder each (ADR-0115 point 3); the text is made of identifiers
   the platform generated, never of typed text. A test (`JsonbQuerySqlRecordSecurityTest`, with a property test) proves that every node prints only fixed fragments and that no value is ever part of the statement.
3. **The index on the owner, `object_record_owner`** (V045): `(tenant_id, object_api_name, owner_id, id) where deleted_at is null`. Tenant first like every index of the table, the object second, the owner third, the identifier last so that the keyset order of a page (ADR-0100) is read from the index. The owner changes in a dedicated operation (ADR-0117 point 5), so the index costs an entry per insert and nothing per ordinary update (updates stay heap-only).
   A filter on `ownerId` (equality or "one of") therefore counts as indexed for the scan limit of ADR-0105; ordering by it does not (a sort key other than the identifier or the creation time still needs a slot).
4. **Measured** (scratch database, PostgreSQL 18, 1.3 million records in one object, forced row level security, before the code was written; the figures are in ADR-0115 point 3): a set of owners as one `uuid[]` parameter finds a first page in 1 to 11 ms for 1 to 5,000 owners, and planning, not execution, grows with the array (0.3 ms at 50 owners, 16 ms at 20,000). The shares add a semi-join read on `record_share_tenant_grantee`
   `(tenant_id, object_api_name, grantee_id, access_level, record_id)`. The benchmark carries the scenarios of Sprint 17 (a Private page, a hierarchy page, a shared page; Public Read/Write is the baseline), run in the regression suite with thresholds fixed before any number was seen (ADR-0082); their numbers are in the sprint document.
5. **One statement for every read path.** `StorageStrategy.find`, `matches` and `liveRecords` take the plan node of the condition (null for none), and `query` and `scan` receive it as part of the plan; a single read adds `and <condition>` to the select by identifier. A write path to the records that reads a record by identifier must pass the condition of its action. `OnlyTheStorageNamesTheTableTest` keeps every other class away from the table.
6. **A cursor is not a grant.** The cursor holds only sort values and a fingerprint of the query (ADR-0100); the member's condition is added again to every page from the description of that moment, so a made-up or an old cursor can never continue under a wider condition than the member has now.

## Consequences

- An object with the default `PUBLIC_READ_WRITE` pays nothing: the description is `Everything` and no node is added.
- A Private object is served by two indexes (owner and share) and the existing slot indexes; the planner choice is visible in the benchmark.
- The limit of 20,000 owners is also the limit of the statement's size; beyond it the description fails closed (ADR-0115 point 3).
