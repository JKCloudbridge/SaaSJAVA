# ADR-0121: The object store, its clean-up and its health

- **Status:** Accepted
- **Date:** 2026-10-13
- **Sprint:** S17
- **Related:** [ADR-0120](0120-files-attached-to-records.md), [ADR-0012](0012-observability.md), [ADR-0090](0090-the-answer-to-object-usage.md)

## Context

A file is two things that cannot be changed in one transaction: a row and some bytes in another system. Whatever the order, a crash between the two leaves one without the other. The decision is which of the two failures is allowed, and how the other is found and removed without anyone looking.

## Decision

1. **The only failure allowed is bytes without a row, and there is always a queue entry or a row for those bytes.** The order is: row first, bytes second, row final. A row without bytes can exist only as `pending`, and a pending row is older than the allowed time (`pending-ttl`, one hour) only when the instance died; the clean-up removes the row. A row is never `active` without its bytes.
2. **Removing a row queues its key, in the same transaction, by a trigger** (V046, `file_object_cleanup`). Every way a row disappears — a person deletes the file, an upload fails, a record is removed for good, the purge of the recycle bin — goes through the same table and the same trigger, so no code path can forget. The queue has a partial unique key (a key is queued once).
3. **`FileCleanup` drains the queue:** per organization, in that organization's own context, in batches (`platform.data.files.cleanup.batch-size`, 200): removes the bytes from the store first and the queue entry second, so a crash between them costs one repeated removal (removing absent bytes is a success) and never a lost key. It also expires the stale pending rows, which queues their bytes. When the store does not answer, the pass for that organization stops, the entry stays with its attempts raised, the instance logs a counted warning. Two instances doing the same work is harmless.
   This is the "periodic check for orphans" and it needs no listing of the store: because every byte has a row or a queue entry from before it exists, nothing is ever an orphan that cannot be found.
4. **The health of the store is a component of its own, `objectStore`,** on the management port (`UP`, `DEGRADED`, `UNKNOWN` when none is configured). It is deliberately never `DOWN`: the status of the whole application is the most severe of its parts, and most requests need no file, so a store that is down must not take an instance out of the load balancer or fail a deployment check. Files answer `503` with a retry hint while it is down. The monitoring alerts
   on `DEGRADED`; the manual steps of Sprint 17 say so. No detail names the host or the bucket. A probe never stores a file: it asks for the bucket.
5. **Short timeouts.** A connection waits seconds; a call can last as long as the largest file on a slow link needs (`socket-timeout` and `call-timeout`). No call holds a database transaction.
6. **Imports use the same store** for the file sent and the report of failed rows (`imports/<organization>/<job>/...`) with their own removal after the retention time (ADR-0123); they are not in the file queue because they are not rows of `record_file`.
7. **The client** is the standard object-storage client library, with the plain Java HTTP connection (no extra HTTP stack), path-style addressing and the "when required" checksum mode so that any S3-compatible store works (verified against the one in the local services with a throwaway probe before the code was written). The library is named only in the build files.

## Consequences

- A crash, a store outage or a redeployment at any point leaves nothing that is not found again by the next pass, and the repair needs no human.
- There is no way to ask the platform "which bytes in the store belong to nothing" — by design nothing does; a deployment that wants the proof lists the bucket and compares with `record_file` and the queue.
- The queue is a table that grows only while the store is down; its length is read from the table.
