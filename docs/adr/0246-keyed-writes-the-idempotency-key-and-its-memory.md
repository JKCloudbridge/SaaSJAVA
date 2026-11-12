# ADR-0246: Keyed writes: the Idempotency-Key and its memory (S30)

- **Status:** Accepted
- **Date:** 2026-10-31
- **Sprint:** S30
- **Related:** [ADR-0003](0003-rls-defence-in-depth.md), [ADR-0010](0010-base-schema-conventions.md), [ADR-0122](0122-bulk-record-operations.md), [ADR-0245](0245-the-public-contract-shapes-numbers-versions-queries-and-bulk.md)

## Context

A program that loses an answer (a timeout, a dropped connection) cannot know whether its create happened. Sending it again
risks a second record; not sending it risks none. The caller needs a way to repeat safely.

## Decision

1. **A header, on two operations.** `Idempotency-Key` (8 to 255 letters, digits and `. _ : ~ = + / -`) is honoured on
   `createRecord` and `bulkRecords`. A change is already safe to repeat (it carries the version it was based on and a
   second one is a `CONCURRENT_MODIFICATION`), a delete answers `CONFLICT` the second time, and a restore answers
   `CONFLICT` when the record is live; none of them needs a key. A key on any other operation is ignored.
2. **What the key means.** The same key with the same request, from the same member of the same organization, makes
   one record whatever the number of attempts. The repeat is answered with the first answer's status (`201` for a create,
   `200` for a bulk call), `Idempotent-Replayed: true`, and the record **as it is now**. The same key with another request
   (another body, object or kind of operation, judged by a hash of the canonical form of the request, which does not depend
   on the order of keys) is `409 CONFLICT` in fixed words that name nothing of either request. A key is one person's: another
   member may use the same text.
3. **The memory is one table, written in the transaction of the write** (`idempotency_record`, V090). The row exists exactly
   when the write was committed, so there is no state in between and no claim to expire. The transaction first takes a
   transaction-scoped advisory lock on (organization, member, key hash), so two requests with one key that arrive together,
   on one instance or two, run one after the other and the second finds the first's row. A request that was refused, and an
   all-or-none call that was rolled back, leave no row: sending them again is a new attempt.
4. **The row holds no value.** It holds: the member, a SHA-256 of the key (**the key itself is never stored**), a SHA-256 of
   the request, the kind of write, the object name, an expiry and the outcome as identifiers only: for a create the record
   identifier and version; for a bulk call, per position, the status, identifier, version and the **code** of a refusal. A repeat
   of a create reads the record again (if it was deleted or can no longer be read by the caller in the meantime, the answer
   is `CONFLICT` in fixed words and the caller sends the request with a new key); a repeat of a bulk call is rebuilt from the
   row, and a refused record keeps its code with fixed words instead of the problems of the first time. This is why a
   stored row can be audited, backed up and read by support without leaking anything a person typed.
5. **Kept for 24 hours** (`platform.publicapi.idempotency.retention`, one minute to seven days). After that the key is free
   again: a lookup removes an expired row of its key first, and `IdempotencyRetention` removes the rest in batches,
   organization by organization, in each organization's own context (no system scope).
6. **The database holds the rules** whatever the application does: forced row level security with a tenant-first partial
   unique index on (organization, member, key hash); shape checks (hex hashes, the two kinds of write, a name-shaped
   object, an outcome under 256 KiB); a row never changes (an update is refused, for the owner of the schema too); a row is
   deleted only after it has expired. `IdempotencyGuardIT` drives each at the database; the isolation harness lists the table.
7. **Where the code lives.** Not in `publicapi`, which reads no table: the contract is `IdempotentWrites` in the `data`
   module, implemented next to `Records` and `BulkRecords` (a keyed bulk call asks for the memory inside the bulk call's own
   transaction, after the gate, so a repeat never tells a caller who may no longer use the object anything).

## Consequences

A repeat shows the record as it is, not as it was; the contract says so. The cost is one small insert per keyed write and
one advisory lock held for the duration of that write. The key, the request and the values never reach a log, an audit
record, an event or a metric (`PublicApiFlowsLogsAreCleanIT` looks for a canary in each).
