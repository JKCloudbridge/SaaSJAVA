# ADR-0101: The index policy and the slots

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S16
- **Related:** [ADR-0082](0082-storage-benchmark-specification-gate-g1.md) (amendment 1), [ADR-0083](0083-storage-benchmark-result-gate-g1.md) (condition 5), [ADR-0084](0084-the-record-store.md), [ADR-0102](0102-the-slot-copy-in-the-write-path.md)

## Context

With row level security forced, PostgreSQL does not use an index on the JSON of a record (the JSON operators and decimal comparisons are not leakproof; measured, ADR-0083). A field that people filter or sort by therefore needs a plain typed column that
carries a normal index: a **slot**. Sprint 14 reserved eleven; the plan (S16) says a field flagged "indexed" gets a slot, the copy is filled for existing records in the background, and the number of slots per type is a hard limit.

## Decision

1. **The flag is intent; the assignment is state.** `field_definition.is_indexed` (V040, an expand migration) says what the organization wants and travels through the change-set lifecycle like `unique`. Which slot holds the copy, and how far the copy is, is
   a row of `field_slot` (V041, tenant-scoped, the usual conventions): `FILLING`, `FILLED`, `CLEARING`; never two fields on one slot, never two slots for one field (partial unique indexes). **A field counts as indexed for queries only when its row is `FILLED`.**
2. **Which types, how many.** Text, e-mail, phone, URL, picklist, auto-number (text slots); number, decimal, currency, percent (number slots, double precision); date; date and time (its own timestamp slots, because a date slot holds only the day and could
   not sort by time); lookup and master-detail (reference slots); checkbox. Not long text, multi-select picklist, time of day, formula (ADR-0095). **Twenty-two slots: text 8, number 4, date 2, timestamp 2, reference 4, checkbox 2** (V041 widened the eleven
   of V036; the benchmark runs on this table). `SlotClass` (metadata root) holds the kinds and the counts; a test compares them with the columns. The limit is a rule of the **result** of a change (`Dependencies.IndexedFields`), so the order of the changes of
   one change set does not matter, and the error names the kind and the fields that use the slots.
3. **Every master-detail field is served by a slot whether or not it was flagged** (`SlotClass.served`): the records that belong to a master are listed by it (a related list) and a junction is read from either parent. It counts against the four
   reference slots.
4. **Reconciliation, not calls.** `IndexPolicy.reconcile()` compares the published flags with the rows and brings the rows in line: a flagged field gets the **lowest free slot of its kind** (the object is locked, see 5), a field no longer flagged (or removed) starts
   `CLEARING`. It runs after every published change (the `metadata.published` event, which `data` handles without a module edge) **and at the start of every pass of the backfill**, so a lost or late event is repaired by the next pass. It is idempotent.
5. **A write never misses its copy.** Every write of a record takes, for the rest of its transaction, the **shared advisory lock** of its object (`RecordSlots`, one statement) and reads the assignments after it. A change of the assignments takes the
   **same lock exclusively** (a try-lock polled for two seconds: it never queues behind a write, because a waiting exclusive request would hold back every new write) and so waits for the writes in flight; any write that starts later sees the new assignments. Without this a
   transaction that read "no slot" before the assignment and committed after it would leave a record without its copy, and no later check would find it.
6. **The backfill is resumable, bounded and shared** (`IndexBackfill`): batches of 1,000 records (one set-based statement computed by the database), each in the organization's own tenant context and transaction, at most 100 batches per field and pass, the cursor
   and the count kept in `field_slot`, one batch of an object at a time across instances (a try-lock for the batch; a batch is repeatable anyway because it writes only a copy that differs), one audit record per field and pass with counts. A copy is called
   complete only after a check over the whole object finds nothing to repair; if it does, the fill starts again and a warning is logged. Records in the recycle bin are not filled: they are not in the indexes, and a restore computes the copy again (ADR-0102).
7. **Un-flagging frees the slot only after it is cleared.** Writes stop copying the moment the row is `CLEARING` and leave the slot empty; the clearing empties what remains; only then the row is freed. A slot is never reused with the values of another
   field in it. A field flagged again while it is being cleared waits for the slot to be freed.
8. **Publish and rollback drive it** like any metadata: a rollback of "flag this field" is "stop indexing it".
9. **Visible to administrators:** `GET /api/v1/objects/{object}/index-status` (ability `metadata.view`): each flagged field's state and records done. No slot name, no value.
10. **Standard fields:** a platform definition file may flag a field (`indexed: true`); none does today (the choice is the organization's). The baseline test records the flag in the line of the field, so a change of it is a visible diff.

## Consequences

- A flagged field is slower to write (an index entry) and fast to filter; an unflagged one is the opposite. The slot indexes are partial (`where slot is not null and deleted_at is null`), so a slot nobody uses holds nothing and costs no write.
- The sprint changes one database invariant: an update that changes slot columns **only** keeps the version and the time of last change (ADR-0102).
- Four reference slots for lookups and master-detail, two checkboxes, two dates: an object that needs more indexed fields of a kind than that is refused with the kind and the fields. Widening is an expand migration.
