# ADR-0106: Record links, delete behaviours and the junction

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S16
- **Related:** [ADR-0063](0063-relationships.md), [ADR-0086](0086-soft-delete-the-recycle-bin-and-its-retention.md), [ADR-0097](0097-links-between-records-on-write.md), [ADR-0101](0101-the-index-policy-and-the-slots.md)

## Context

ADR-0063 stores, with each link field, what happens to a record that points at a removed one: `CLEAR`, `REFUSE` or `CASCADE`. Nothing carried it out, because that needs the index of who points at whom (Sprint 14 refused to build even "refuse" without it).

## Decision

1. **`record_link` (V042), tenant-scoped: one row per reference value of a record** (source object, source record, field, target object, target record), written in the transaction of the write that sets, moves or clears the value. It does not depend on which fields are flagged as indexed, so "who points at
   this record" is always answerable. A record removed for good takes its rows with it (a database trigger, so the clean-up of the recycle bin cannot forget). A record in the recycle bin keeps its rows.
2. **`link_index` (V042) says whether the rows are complete for records that existed before the table.** A bounded, resumable pass (`LinkBackfill`, run by the same sweep and the same published-change event as the index policy) writes the missing rows of each reference field, locking the records of a batch while it reads
   them so that a change running at the same time cannot leave a row that is no longer true. **Until a field is complete, removing a record it could point at is refused** ("still being prepared, try again in a few minutes"): not knowing who points at a record is not a licence to remove it.
3. **What removing a record does** (carried out in the same transaction, planned first and refused as a whole if any part is refused; nothing is half done):
   - **CLEAR** (a lookup): the pointer of every live record that points at it is emptied as a change of that record by the platform (new version, the copy and the row updated, the event `record.updated`), and the records keep everything else.
   - **REFUSE** (a lookup; always for a required lookup, whose record would be invalid without it): `CONFLICT` "Other records refer to this record, so it cannot be removed" and **nothing else**: no name, no count, no object, because the pointing records may be records the caller may not see.
   - **CASCADE** (a master-detail): the live details go to the recycle bin with the master, each marked with it (`deleted_with`), recursively (a detail with details of its own), event `record.deleted` for each.
4. **Permissions.** Removing details through a master needs the permission to delete in each detail object as well; otherwise `FORBIDDEN` "You may not remove the records that belong to this one": a member must not remove through a master what they could not remove directly. (Record-level security, Sprint 17A, will add the
   per-record rule.)
5. **Bounded.** A removal that would remove or change more than `platform.data.links.max-affected` (10,000) other records is refused with the bound, so that one request never holds an unbounded transaction; a job or an administrator decides what to do with such a record. One audit record per removal with the counts
   (`data.record.removal`), never per cascaded record; the events stay per record, because an external system needs each.
6. **Records in the recycle bin do not count.** They do not block a removal and are not changed by one.
7. **Restoring** a record checks its links first: a master-detail whose master is gone (in the bin or removed for good) **refuses the restore** ("restore the record it belongs to instead"); a lookup whose record is gone is emptied after the restore. The restore writes the record's rows again and brings back the records
   that were removed **with it** (`deleted_with`), recursively, up to the same bound, and not the details a person removed on purpose before.
8. **Moving a detail to another master** (`reparentable`) updates its row in the write; a link written for the first time inserts it.
9. **The junction.** An object with exactly two master-detail fields is a many-to-many association between the two parents. Both of its master-detail fields are served by a slot automatically (ADR-0101), so it is **queryable from either parent** with a filter on the field (`EQ` on the identifier) and a bounded page, like any
   query. **One link per pair** is enforced by the write pipeline: when a write creates a junction record or moves either master it takes a transaction lock on the pair and refuses a second record for the same two parents ("These two records are already linked"); two writes of the same pair at once cannot both pass.
   A database unique constraint on the pair is part of the uniqueness work of Sprint 16A, which will replace the lock.
10. **A link to a record of another organization is never counted:** the rows are tenant-scoped (forced row level security) and a pointer to another organization cannot be written (ADR-0097).

## Consequences

- One statement more per write that sets or clears a link; one per removal to find who points at the record, and one batched question per pointing object to learn which of them are alive.
- A formula on a record whose lookup was cleared keeps its stored result until the record is written or the recalculation of Sprint 16A runs (ADR-0095).
- Platform-owned targets (a user) are recorded in `record_link` too; removing a user is not a removal of a record of the store, so no behaviour applies to them.
