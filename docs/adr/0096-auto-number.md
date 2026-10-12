# ADR-0096: Auto-number

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S15
- **Related:** [ADR-0060](0060-field-types-and-their-configuration.md), [ADR-0091](0091-the-write-pipeline-and-its-order.md)

## Context

An auto-number field gives each new record the next number of a running count, written as its settings say: a prefix, a first number and a width (`INV-000123`). It belongs to one field, so to one object, and in the same object two
auto-number fields count separately.

## Decision

1. **The count lives in `auto_number_counter`** (migration V037): one row per organization, object and field, with the number the next create receives. The table follows every convention of a tenant table (base columns, row guard,
   tenant guard, tenant-first partial unique index, forced row level security) and a guard that keeps the count from going backwards or to another field.
2. **Taking a number is one statement** that creates the row at the field's first number or moves it on (`insert ... on conflict do update ... returning`). Two instances creating records at once therefore get two different numbers,
   and a number is **never given twice**, not even after its record is deleted (the row is never deleted).
3. **Given on create only**, never on update. Nobody writes it (ADR-0092). It is stored in the payload like any value, as text: prefix, then the digits padded with zeros to the width. A number longer than the width is not cut.
4. **Taken as late as possible.** It is the last thing a create does before storing the record, after every check that can refuse: the row stays locked until the transaction commits, so creates of the same field wait for each
   other only for as long as the rest of one create takes. A refused or failed create gives the number back with everything else (same transaction).
5. **Gaps are allowed** after a create that is rolled back after the number was taken (for example a conflict at storage time), and a number is never re-used. A count without gaps would make every create of the object wait for the
   one before it to commit, which the benchmark's bulk target (5,000 records a second per worker) does not tolerate. If a gap-free promise is ever needed it is a different design (a number given at commit by one worker).
6. **Changing the settings later** (another prefix, another first number) never moves the count back: the digits go on from the counter and the prefix is the current one. Records already numbered keep their number.
7. **Which fields:** every auto-number field of the object that is not retired and that the record type offers, in field name order (so two creates never take two locks in opposite orders).

## Consequences

- Two instances racing on one field are covered by `AutoNumberConcurrencyIT` (two real application instances, no duplicate).
- A record created through another path than the pipeline would not be numbered; there is none (ADR-0088: every write goes through `Records`).
