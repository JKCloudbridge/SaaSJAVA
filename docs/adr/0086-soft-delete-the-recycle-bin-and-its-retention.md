# ADR-0086: Soft delete, the recycle bin and its retention

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S14
- **Related:** [ADR-0010](0010-base-schema-conventions.md), [ADR-0054](0054-audit-v1-record-source-and-read-model.md), [ADR-0084](0084-the-record-store.md), [ADR-0089](0089-what-a-record-may-write-and-what-it-may-never-leak.md)

## Context

Deleting a record by mistake must be recoverable for a while, and nothing may be kept forever. A deleted record is still a record: it holds values (personal data may be among them), it counts for the object (ADR-0090), and it must leave
the database in the end.

## Decision

**Three states.** Live (`deleted_at` empty), in the recycle bin (`deleted_at` set), gone (the row is deleted).

| Action | Needs on the object | Live record | Record in the bin | No such record |
|--------|---------------------|-------------|-------------------|----------------|
| read, change | read, update | works | read: not found; change: **409 CONFLICT** ("it is in the recycle bin, restore it first") | 404 |
| delete | delete | moves to the bin (version +1) | **409 CONFLICT** (deleting twice) | 404 |
| restore | delete | **409 CONFLICT** (not in the bin) | works (version +1) | 404 |
| remove for good | delete | **409 CONFLICT** (delete it first) | works (a plain delete of the row) | 404 |
| list the bin | delete | not listed | listed, in identifier order | |

- **Why delete is needed to list the bin.** A member who may delete records of an object may see what they deleted; a member who may only read must not see records that were taken away. Until record-level security (Sprint 17) this is wider than the
  finished product will be, like reading (ADR-0089).
- **A deleted record is read-only** in the database too (the row guard of ADR-0010), so a bug cannot edit a record in the bin. Deleting and restoring are changes: each raises the version.
- **Retention.** A clean-up (`RecycleBinRetention`) removes records that have been in the bin longer than `platform.data.retention.recycle-bin` (default **30 days**, at least one second), at most `batch-size` (default 500) per organization per pass,
  every `interval` (default one hour), on every instance that has `enabled` on (two instances at once is harmless: the second delete matches nothing the first one already removed; a locking clause was tried and dropped, because under forced row level security it made PostgreSQL match no rows at all). It works organization by organization **in that
  organization's own tenant context and transaction**: row level security stays on, no system scope is needed, and one organization failing does not stop the others.
- **What a removal leaves.** Every record removed for good, by a person or by the clean-up, writes the event `record.purged` in the same transaction; a person's removal writes an audit record (`data.record.purged`); a pass of the clean-up writes one
  audit record per organization (`retention.record.purged`, with the count), visible to the organization and the platform. Nothing of the record's values.
- **What retention does not do.** It does not remove records of a deactivated organization specially (that is the lifecycle work of Sprint 34), and it does not protect a record from a legal hold (not built).

## Consequences

- The period is a setting, so a deployment can lengthen or shorten it; a manual-only purge is "set a very long period" (the clean-up can be switched off per instance).
- A removed object or field waits for the bin (ADR-0090): an object whose only records are in the bin cannot be removed until they are restored or removed for good, by a person or by the clean-up.
