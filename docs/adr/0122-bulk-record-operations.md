# ADR-0122: Bulk record operations

- **Status:** Accepted
- **Date:** 2026-10-13
- **Sprint:** S17
- **Related:** [ADR-0091](0091-the-write-pipeline-and-its-order.md), [ADR-0115](0115-the-record-access-model-and-how-it-reaches-a-statement.md), [ADR-0123](0123-csv-import-jobs.md), [ADR-0108](0108-limits-per-organization.md)

## Context

Integrations and screens need to create, change or delete many records in one call. The risk of a bulk path is that it becomes a second, faster, less careful way to write: its own validation, its own audit, a place where a rule is forgotten. The sprint also needs a path for the import (ADR-0123) that is exactly as careful as a single write.

## Decision

1. **There is no private path.** Every record of a bulk call goes through `Records.create`, `update` or `delete`, the code a single request runs: the gate, field security, the pipeline (types, required, relationships, unique, rules, formulas, auto-numbers), the typed copies and the unique mask, the links, the audit record and the event of each record. Whatever Sprint 17A adds to that code (the record permission stage) applies to a bulk call without a change.
2. **One transaction, one savepoint per record.** By default each record stands alone: a refused record is rolled back to its savepoint (with its audit record and its event, which are rows of the same transaction) and the others are saved. With `allOrNone: true` the first refusal ends the call with everything rolled back: nothing is saved, the record that failed keeps its problems and the others report `ROLLED_BACK`. Anything that is not a refusal of
   the request (the database or the store failing) ends the call with the usual error and saves nothing.
3. **The answer has one outcome per record, at its position:** `CREATED`, `UPDATED`, `DELETED`, `FAILED` (with the same code, message and per-field problems a single request would have answered) or `ROLLED_BACK`; counts of each; and `committed`. Messages never repeat a value, as everywhere.
4. **Operations:** `CREATE` (fields), `UPDATE` (identifier, the version read, the fields to change), `DELETE` (identifier; to the recycle bin). One operation per call. An item without what its operation needs, or with what it must not have, fails alone with plain words.
5. **Bounded:** at most 200 records and 8 MiB a call (`platform.data.bulk.*`); more is refused with `400` before anything is read or written, the body limit before the body is read. The limits of the organization (concurrency, statement time, ADR-0108) apply to the call as to any request.
6. **Audit:** every record has its own record as always, plus **one summary** `data.bulk.completed` (operation, counts, `allOrNone`, `committed`) written in its own transaction so that it exists also when the call was rolled back. No value of any record is in it.
7. **Concurrency:** a stale version is the outcome of that record (`CONCURRENT_MODIFICATION`). Two calls at once that race for the same unique value cannot both store it: the pipeline's check and the database's unique index (ADR-0111) decide, and the loser's record fails alone.
8. **Not a job.** A bulk call is synchronous and small. Large loads are imports (ADR-0123), which are jobs.

## Consequences

- A bulk call is as slow as the same records written one by one, minus the round trips; the benchmark measures it (the scenario of Sprint 16 for the bulk upsert is the baseline and its pending finding is reported in the sprint document).
- Because savepoints are cheap and the work of a record is the same code, the correctness of a bulk call is the correctness of a single write, which is what the tests (`BulkRecordsIT`) hold it to.
