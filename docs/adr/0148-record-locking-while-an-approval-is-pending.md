# ADR-0148: Record locking while an approval is pending

- **Status:** Accepted
- **Date:** 2026-10-18
- **Sprint:** S24
- **Related:** [ADR-0145](0145-the-approval-model-processes-versions-instances-and-states.md), [ADR-0091](0091-the-write-pipeline-and-its-order.md),
  [ADR-0106](0106-record-links-and-delete-behaviours.md), [ADR-0114](0114-recalculation-of-stored-formula-results.md),
  [ADR-0122](0122-bulk-record-operations.md), [ADR-0125](0125-record-level-security-on-writes-links-and-uniqueness.md),
  [ADR-0139](0139-background-writes-the-one-reviewed-path.md)

## Context

An approver must decide on what was submitted. While an instance is `PENDING` the record must not change by any path. "Every path" is the
kind of claim that rots when each path checks by hand, and the first draft of this sprint named a place that cannot hold it:
`RecordGate.pass` takes an object and an action and has **no record identifier**.

## Decision

1. **One class does the guarding: `RecordLockGuard` in `data`.** Every place that changes a stored record asks it, and nothing else decides.
   It asks a shared-kernel contract, **`RecordLocks`**, which `approval` implements (`data` must not depend on `approval`; the same
   inversion as `RecordMatcher` and `ReferenceDirectory`). The seams, all of them, are:

   | Path | Seam |
   |------|------|
   | update, delete, restore, purge (single, bulk and import go through `Records`) | `DefaultRecords.reachable`, the record permission stage |
   | share, unshare, change owner, attach and remove a file | `RecordAuthorization.forEdit` |
   | a workflow's write | `DefaultBackgroundWrites.update` |
   | **a cascade, a pointer cleared, a restore of details** | `RecordRemoval` (below) |
   | the approval engine's own outcome update | allowed: it runs after the instance has ended, in the same transaction |

   A **source-scanning test** (like `OnlyTheStorageNamesTheTableTest`) fails the build when a class outside a short allow-list calls a
   writing method of `StorageStrategy`, so a new path cannot skip the guard. The allow-list names its reason for each entry: the guard
   itself, formula recalculation (a calculated copy, ADR-0114), index and link back-fills (typed copies and links, no new version), the
   recycle-bin retention (only records that were deleted, so never pending) and the search index.
2. **Shares are allowed on a locked record; the owner and files are not.** A share changes who may see the record, not what is approved,
   and it grants nothing to an approver (ADR-0146). Changing the owner changes a field of the record; a file is part of what is judged.
3. **A locked record is read-only for everyone, the submitter included.** A member with the ability `approvals.override-lock` may still
   change it; that is **audited** (`approval.lock.overridden`, the record, the object, the member; never a value) and the history of the
   instance says so. The platform cannot unlock: it has no business authority inside an organization. A person with the override ability
   may **cancel** the instance, which unlocks.
4. **The refusal** is `409 RECORD_LOCKED` with fixed words that name no process, no step and no person. A workflow action that meets a
   locked record fails its step with the stable reason `record_locked`, **permanent** (ADR-0142 classification): a replay after the
   approval ended is the answer. Deleting a master whose cascade or cleared pointers reach a locked record is refused with the same code
   ("a record this change reaches is under approval"); this is a small existence leak (the caller learns that some record they may not be
   able to see is being approved), of the same kind as the existing delete-behaviour refusals of ADR-0106, and is accepted.
5. **An object with no approval process costs nothing.** The guard first asks the metadata snapshot whether the object has **any
   approval process row at all, active or not**. It does not ask whether one is *active*: a switched-off process, or an organization that
   lost the feature, can still have pending instances that must keep their lock (ADR-0152). Only an object with a process row pays the cost
   below. A process cannot be removed while one of its instances is pending, so "no process row" implies "no pending instance". A counter
   test sums every lookup over a run on an object with no process and asserts zero. The snapshot is the artefact of the metadata version
   current when the transaction first asked (ADR-0061): a write that began before a process was published, and ends after a first
   submission, can pass unguarded. This is the same bound every metadata-derived rule has, and is stated here so nobody expects more.
6. **The race, and the order that closes it.** Without care a writer checks "not locked", a submit commits the lock, and the writer then
   commits: the record changes after the approval began. A row lock on the record alone does not close it (a submit does not change the
   record's version). So:
   - the **guard takes the record's row lock first** (`select ... for update`) and **then** reads the lock (a new statement, so under
     READ COMMITTED it sees whatever committed in the meantime);
   - **submit takes the same row lock first**, re-reads the record, judges the entry criteria on it, and only then inserts the instance;
   - a cascade or a bulk locks the records it touches **in identifier order** so two of them cannot deadlock;
   - a decision (`approve`, `reject`, `recall`, `cancel`) takes the **record row first** and the **instance row second**, because the
     partial unique index makes a second submit wait on an uncommitted instance and the other order deadlocks.

   **The lock order of the platform** is therefore: access lock, member row, licence pool row, **record row, instance row, step and
   assignment rows**. A two-thread test (`RecordLockRaceIT`) holds a writer between its check and its commit while a submit runs.
7. **The lock and the instance change in the same transaction.** The lock *is* the instance in `PENDING` (there is no separate lock row to
   forget), so an unlock cannot be lost and a crash cannot leave a record locked: the instance always ends (approve, reject, recall,
   cancel). The cost for an object that has a process is one extra row lock and one indexed read per write.
8. **Formula recalculation** keeps working on a locked record. The stored result of a formula is a calculated copy, not a change; the
   row guard already lets it through without a new version (ADR-0114).

## Consequences

- One class to read, one test that fails when a path is added without it, one order to remember.
- A locked record cannot be edited by its owner who needs to fix a typo: they recall, edit, submit again. That is deliberate.
- A bulk or an import that includes a locked record gets `record_locked` for that record only (the bulk and import report per record).
