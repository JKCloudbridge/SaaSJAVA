# ADR-0103: Indexed and unindexed paths answer alike

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S16
- **Related:** [ADR-0095](0095-formulas-stored-on-write.md), [ADR-0100](0100-the-query-model-and-its-stages.md), [ADR-0102](0102-the-slot-copy-in-the-write-path.md)

## Context

The same question can be answered from a slot (with an index) or from the values of the records (a scan), depending on whether an administrator flagged the field and whether the copy is complete. If the two paths disagree, flagging a field would change the answers, which is a
data defect that nobody sees.

## Decision

The semantics of every comparison are defined once, in `JsonbQuerySql`, and both paths use them:

1. **A value that is not of the kind of its field is no value.** A stored value that does not parse as the field's kind (a record written before the types were checked, Sprint 14) reads as empty on both paths (`pg_input_is_valid`, never an error); a query never fails because of
   one bad record.
2. **Records without a value come last, in both directions**, and `IS_EMPTY` / `IS_NOT_EMPTY` mean "no usable value" / "a usable value".
3. **`NE` matches only records that have a value that differs** (SQL's own rule); to include the empty ones, combine with `IS_EMPTY` in an `OR`.
4. **Text is compared exactly** in the database's collation (equality is exact; the order of `GT`, `LT` and sorting is the database's, the same on both paths, because the slot index is built on the column's own collation). `STARTS_WITH` and `CONTAINS` are exact and case-sensitive and never use a pattern.
   Sorting is therefore as the database orders text, not case-insensitive; a sort key for case-insensitive order would be a slot of its own (not built).
5. **Numbers are compared exactly.** In a slot (double precision) the comparison is only a net that cannot miss a record, followed by the exact comparison on the record's values; without a slot only the exact comparison is made. Two decimals that differ beyond the precision of a double are different
   in every filter and, in a sort, are ordered by the slot and then by identifier.
6. **Days and times compare as days and times** (PostgreSQL `date` and `timestamptz`, microsecond), identifiers as identifiers.
7. **`id` and `createdAt` are read from the columns of the record**, the other system fields (`ownerId`, `updatedAt`, `createdById`, `updatedById`) too, and are indexed or not as ADR-0102 says; a filter on one that is not indexed follows ADR-0105.
8. **A differential test holds the two paths to each other** (`QueryDifferentialIT`): the same generated records and the same questions, once with the fields flagged and filled and once not, for every operator on every indexable type, including decimals near the limit of a double, empties, days, the recycle bin and
   a restored record; the answers must be identical, page by page.

## Consequences

- Flagging a field is a speed decision and never a meaning decision.
- Two numbers that agree in 17 digits are one number in the slot net and two numbers in the answer, which is what the exact comparison is for.
