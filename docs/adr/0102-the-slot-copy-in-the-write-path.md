# ADR-0102: The slot copy in the write path

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S16
- **Related:** [ADR-0091](0091-the-write-pipeline-and-its-order.md), [ADR-0101](0101-the-index-policy-and-the-slots.md), [ADR-0103](0103-indexed-and-unindexed-paths-answer-alike.md)

## Context

ADR-0091 left a seam (`RecordSlots.copyIndexed`) and said where the slot parameters go. A slot is only worth having if it is never wrong: a record whose copy differs from its values is a record a filter silently misses.

## Decision

1. **The copy is written in the same statement as the record.** `INSERT`, `UPDATE` and the restore statement of `JsonbRecordStorage` carry all twenty-two slot parameters; a slot that is not assigned, or that the record has no value for, is written empty. The pipeline calls the
   seam once per successful create and update with the values the record will have (after defaults, auto-numbers and formulas), so the copy is the record's own final values. An update sets every slot, so it can neither leave an old copy nor miss a new one; an unchanged value
   is not a change to PostgreSQL, so an update that touches no indexed value stays a heap-only update.
2. **A record written while its slot is being filled gets its copy** (the seam answers for `FILLING` as well as `FILLED`), otherwise the fill could never call itself done. A slot being cleared is not copied.
3. **All values travel as text and the database converts them**, with the same expression the backfill uses (`SlotColumn.fromParameter` / `fromPayload`): a number goes `text -> numeric -> double precision`, a day `-> date`, a time `-> timestamptz`, an identifier `-> uuid`.
   A write and a backfill therefore cannot disagree about a conversion.
4. **A number in a number slot (double precision) is a wider net, not the answer**: queries compare in the slot with the wider comparison that no rounding can make miss a record (conversion to double never reverses the order of two numbers: `>` becomes `>=` in the slot) and then compare
   again, exactly, in the values of the record (ADR-0103). A decimal of more than about 15 significant digits is exact in the record and approximate in the slot; sorting by it orders by the slot (ties by identifier), which is stated in the API description.
5. **A date and time has its own timestamp slots** (V041), exact to the microsecond, so a filter, a sort and a cursor on it are exact; no day-only copy and no second comparison.
6. **A restore computes the copy again** from the record's values. A record in the recycle bin is not in any slot index and is not touched by the fill or the clearing, so its copy may be old; recomputing at the moment it comes back makes it right whatever happened meanwhile (a field indexed or
   un-indexed, a slot reassigned).
7. **Maintenance of a copy is not a change of the record.** Filling or emptying a slot rewrites a copy of a value that did not change. Counted as an update it would raise the version of every record of the object (every open form would meet a conflict) and set "last changed" to now. The row
   guard (`platform_row_guard`, V041) therefore lets **an update that changes slot columns and nothing else** keep the version and the times; any other update still needs version + 1. The test is in a helper that lists every column of the record, so a migration that adds a column must add it
   there (a test fails when one is missing; V042 did exactly that for `deleted_with`). The check runs only for the record table, only when the version is unchanged, and is nested in the function so that PostgreSQL does not try to plan it for other tables.
8. **Not indexed, on purpose:** the time of last change (it changes on every update and would end heap-only updates), the owner (Sprint 17 designs its index with record-level security). The creation time has an index (`object_record_created`): "newest first" is the commonest list there is and the value never changes.

## Consequences

- A write of an object that has indexed fields pays one index entry per indexed field, and one statement more (the lock and the assignments, ADR-0101); the benchmark measures it on the write scenarios (ADR-0109).
- The seam and the statements are held together by a differential test for every indexed type: create, update, clear, restore, the recycle bin.
