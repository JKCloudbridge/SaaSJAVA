# ADR-0111: Uniqueness of field values

- **Status:** Accepted
- **Date:** 2026-10-13
- **Sprint:** S16A
- **Related:** [ADR-0091](0091-the-write-pipeline-and-its-order.md), [ADR-0101](0101-the-index-policy-and-the-slots.md), [ADR-0102](0102-the-slot-copy-in-the-write-path.md), [ADR-0112](0112-the-pair-of-parents-of-a-junction-is-unique.md)

## Context

A field flagged unique has been stored and checked for shape since Sprint 10, but nothing stopped two records from holding the same value: enforcing it needs an index, and an index on a value needs a typed slot (ADR-0083, condition 5), which is Sprint 16's index policy. The delivery plan asks for
"a partial unique index per organization and object". That cannot be made at run time: creating an index for an organization's field would be data definition driven by an organization's metadata, one index per field per organization on one shared table, which the platform's standing rule (no SQL from tenant
metadata) forbids and which would not scale to thousands of organizations. The decision below keeps the plan's intent (the database refuses the duplicate, in the same statement that writes the value) with indexes that are made once.

## Decision

1. **The database enforces it, through a mask.** `object_record.unique_mask` (V043) holds one bit per constraint; the migration makes **28 static partial unique indexes** once: one per slot column (bits 0 to 21) and six for the pairs of link slots (bits 22 to 27, ADR-0112), each
   `unique (tenant_id, object_api_name, <slot>) where <slot> is not null and deleted_at is null and (unique_mask & <bit>) <> 0`. A record takes part in an index only when its own bit is set. The application sets the bit in the same statement that writes the value (`SlotCopy.uniqueMask`,
   next to the typed copies, ADR-0102), for exactly the fields that are unique; every other record is outside the index and costs it nothing. A record in the recycle bin is outside every index (`deleted_at`), and a restore re-enters it, so the database also refuses a restore that would duplicate a value.
   `UniqueKey` is the one list of constraints, their bits and index names; `UniqueFieldsIT` compares it with the database.
2. **A unique field is indexed by being unique.** `SlotClass.served` counts a unique field like a flagged one, so it gets a slot, the copy is filled in the background like any other (ADR-0101) and the limit per kind counts it. When the slots of a kind are used up the change is refused as a rule of the result, with the
   kind, the limit and the fields that use the slots ("has 9 indexed or unique text fields ... at most 8"); that is its own error, not a silent wait. A field that is both flagged and unique uses one slot.
3. **Which fields.** Text, e-mail, phone, number, decimal, lookup and master-detail (the types that allow unique, FieldType). Three more rules, because a slot is a copy and not the value: a **number or decimal is unique only with at most 15 digits** (a slot holds a double, which tells every number of 15 digits
   apart and could take two different longer numbers for the same one); a **web address cannot be unique** (it can be longer than an index entry may be); an **auto-number** and the **system fields** need no check (the counter hands a number out once and nobody writes the field; the identifier is a column of its own). A field flagged unique whose
   setup cannot be checked, which can only be a definition older than this rule, is reported `NOT_ENFORCED` in the status and is not enforced; editing the field fixes it.
4. **Comparison is exact.** A text is compared as with `=`: upper and lower case are different values (the answer of Sprint 13 for text comparison, ADR-0095 point 6); an administrator who wants case-insensitive uniqueness has no tool for it yet and the answer says so in the guide. A number is compared by value (1.0 and 1.00 are one
   value). **A record with no value takes no part**: any number of records may leave the field empty.
5. **The recycle bin does not count.** A record in the bin never blocks a value. A **restore** that would create a duplicate is refused with 409 `CONFLICT` "This record cannot be brought back: another record has the same value in a unique field (<field>) ...", naming the field and nothing about the other record; the
   restore of a master that would bring back details does the same for any of them, and nothing is changed.
6. **A field cannot be made unique over duplicates.** The change that makes a field unique asks the data module (`ObjectUsage.duplicates`) how many live records share a value with another live record, reading the stored values (the copy may not exist yet); if the answer is not zero the change is refused with
   `VALIDATION_ERROR` at `unique`: "N records have a value in this field that another record has too ...", the **count and no value, no record**. The same check guards a rollback that makes a field unique again. A scan of a large object that does not finish within the statement limit is refused with the reason ("could not be checked in
   the time allowed") rather than allowed unchecked.
7. **Switching it on is a state, not a moment.** Each enforced constraint is a row of `unique_enforcement` (V043, tenant-scoped, forced row level security): `ENFORCING` (writes carry the bit and check existing values from the first write; the records that existed before get their bit in bounded batches,
   `IndexBackfill`, resumable), `CONFLICT` (two existing records hold the same value: the batch that meets them fails as a whole, the constraint records how many records collide and waits; the check resumes by itself when the count is zero, so fixing the data is enough) and `ENFORCED` (every live record that has a value carries its bit; a constraint never goes back from it, a guard says so).
   It starts when the copy of the field is complete (every record has the value the constraint compares) and is created and stopped under the same exclusive lock of the object as the slot assignments (ADR-0101), which waits for the writes in flight. `GET .../index-status` shows `WAITING`, `ENFORCING`, `CONFLICT` (with the count) or `ENFORCED`
   for every unique field.
   **The window.** Between the publication and `ENFORCED` the platform is still checking in the old records. New values are checked against every complete copy from the first write, so no new duplicate can be made; a duplicate that was written before the change and slipped past the check at publication (a write in flight) is found
   by the batch and shown as `CONFLICT` with its count. Nothing is lost and nothing is silently accepted, but until the data is fixed the field is not fully enforced.
8. **The write pipeline gets a stage, "unique".** After "relationship" and before "rule" (the stage table of ADR-0091 and `WritePipeline.STAGES` are updated): for each constraint whose value the write changes, the storage is asked whether another live record holds it (`valueTaken`, one indexed read, the record's own identifier excepted);
   if so the write is refused with `VALIDATION_ERROR` at the field: "Another record already has this value. This field must be unique." The words never repeat the value or name the other record. The check runs after the shared lock of the object is taken and the constraints are read (`RecordSlots.begin`), so a write never misses a constraint
   that is being switched on. **The unique index is the backstop for what two writes at the same moment let through:** its violation is translated by the storage into the same answer at the same place, and the write is rolled back with its auto-number and everything else.
9. **Switching it off** frees the constraint row; the bits records still carry are harmless (a record that is written again sets its mask anew, a stale bit on an untouched record only matters between two records that were already unique, and stays harmless), and emptying the slot clears every bit that covers it, so a slot is never reused with a
   bit of the field before it.
10. **Nothing typed reaches a log.** The database's message for a duplicate quotes the value; the storage extracts the name of the index from it and throws an exception that carries the number of the constraint and nothing else, with no cause. The logger of the SQL error translator, which prints that message when it is switched to
    debug, is pinned at warnings in `application.yml`. A test (`UniquenessAndRecalculationLogsAreCleanIT`) holds this.
11. **Record-level security (Sprint 17) and existence.** A refusal tells the member that the value is taken, which a unique field cannot avoid: if they may not see the other record they still learn that it exists. That is the nature of the constraint and is stated here and in the guide; it is not a reason to leave the
    constraint out.

## Consequences

- A write of an object with a unique field pays one indexed read (only when the value changes) and one more index entry; the benchmark has scenarios for both (`s02u`, `s09u`, run only on request).
- Adding a column to `object_record` needs nothing here (the mask is the maintenance of the copies: the helper of the row guard does not name it on purpose, and a test says so).
- A new write path to the records must call `RecordSlots.copyIndexed` (it already must, ADR-0101) and so carries the mask; a restore and the clearing of a pointer already do.
- Wider unique numbers, case-insensitive uniqueness and a unique web address are not built; each is a new kind of key (a normalized copy), not a change to this design.

## Alternatives considered

- **A side table of keys, maintained by every write path.** Works for any type and needs no slot, but every path that writes, deletes, restores or purges a record must remember to maintain it (the pointer table of Sprint 16 shows how that goes), and a missed path is a silent duplicate. The mask rides in the statement that already
  writes the copies and the bin is handled by the index itself.
- **An index per organization and object, made at run time.** Forbidden (data definition from tenant metadata), and it does not scale on one table.
- **Application locks only.** Not enforced by the database, so a bug or a second writer breaks it; kept as the pre-check and nothing more.
- **Case-insensitive by default.** Rejected: it would differ from every comparison in the platform (ADR-0095 point 6) and from the filter on the same field.
