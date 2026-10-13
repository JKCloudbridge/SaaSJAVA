# ADR-0113: Recalculation of stored formula results

- **Status:** Accepted
- **Date:** 2026-10-13
- **Sprint:** S16A
- **Related:** [ADR-0077](0077-formula-fields-are-checked-and-are-edges-of-the-graph.md), [ADR-0086](0086-soft-delete-the-recycle-bin-and-its-retention.md), [ADR-0091](0091-the-write-pipeline-and-its-order.md), [ADR-0095](0095-formulas-stored-on-write.md), [ADR-0101](0101-the-index-policy-and-the-slots.md), [ADR-0114](0114-formula-fields-in-filters-and-orders.md)

## Context

A formula is calculated and stored when a record is written (ADR-0095). Editing a formula leaves the stored result of every existing record as it was, until somebody saves that record again. ADR-0095 left "a recalculation job per object" for the sprint that has the query engine, because a job that pages through the records of an object at scale is exactly
what the query engine does and because a filter or an order on a formula (ADR-0114) needs results that belong to the formula of today.

## Decision

1. **One calculation.** The results of a record are worked out by `Calculations.formulas`, the stage a create and an update run, from the values the record holds (`FormulaResults`); there is no second implementation of formulas. A result that no longer exists (an input is missing) is removed, as a write removes it.
2. **The records are read with the query engine.** `QueryService.scan` runs the same stages and the same statement as a member's query, for a caller who may read every field and has no record security, in identifier order, one bounded page at a time, and without the refusal of a scan of a large object (the batch bounds the work, not a person's request). It is
   reachable only from the platform's own background work.
3. **A fingerprint says what the results are current for** (`FormulaStamp`): a hash of the API name, the result type and the text of every formula field of the object that is not retired, in the order of the names. Editing, adding or removing a formula changes it; a label or another field does not. It is computed from the published metadata alone,
   so every instance agrees and no event is needed. `formula_recalculation` (V043, tenant-scoped) keeps, per organization and object, the fingerprint the stored results are current for, and the pass in flight. The results are **current** when the object is at rest and the two fingerprints are equal.
4. **A record is written only when a result changed, and then without a new version and without a new time of last change.** The recalculation is not an edit a person made; counted as one it would raise the version of every record (every open form would meet a conflict) and put a time and nobody's name in "last changed". `platform_row_guard` (V043) lets exactly this
   update keep the version: only the payload and the typed copies differ, the record is live, and the transaction says so (`set_config('platform.record_recalculation', 'on', true)`, transaction-local, switched off after the statement). Without the setting a change of the payload still needs the next version (a test holds both). The write is
   conditional on the version the results were calculated from; a record that someone changed meanwhile is left alone (whoever changed it recalculated the formulas on the way, and the closing look below finds the rest). Formula results have no typed copy (ADR-0114), so no copy needs maintaining. No event is published per record.
5. **One audit record per finished pass**, `data.formulas.recalculated`, with the counts examined, changed and unavailable and what started the pass (`PUBLISH`, `MANUAL` or `RESUME`); never a record, a value or a formula. A manual start writes one more, `data.formulas.recalculation.requested`, by the person.
6. **A formula that cannot be calculated for a record** (a division by zero) removes the stored result of that formula in that record, because a result that no longer belongs to the formula is worse than none, and counts the record as *unavailable*. (A save of that record is refused as before, ADR-0095 point 4.)
7. **Per organization, bounded, resumable, shared.** In the organization's own tenant context, one transaction per batch (`platform.data.recalculation.batch-size`, 500), at most `batches-per-pass` (100) batches per object per pass, a try-lock per object so that two instances never work on the same object's batch, the cursor and the counts in the row, so a crash, a restart or
   another instance carries on from where the pass stopped. A pass over an object that is already being worked on is not started twice (one row per object). A pass that reaches its bound with records left asks to be run again at once, and one that has only the closing look left, after the delay of the look, so a large object does not wait for the next sweep.
8. **The closing look.** A write that began before the formula was published may end after the pass has looked at its record, with the old results. So the first sweep is followed by a delay (`closing-delay`, 30 seconds: more than the longest write can take, the time limit of a write) and then by a look at every record changed since a little before the
   sweep began (the margin is the same delay, because a write has the time at which it began). That look reads only the records that match, by the time of last change. The pass is finished when it has gone through them.
9. **What starts a pass.** (a) The event of a published change marks every object whose fingerprint differs (`FormulaRecalculationEvents`, consumer `data.formula-recalculation`). (b) A manual start for administrators who want the results checked (for example after records were changed by a path that does not calculate formulas): `POST
   /api/v1/objects/{object}/formula-recalculation`, ability `metadata.publish`, answer 202 with the state, nothing else; asking again while a pass is waiting or running changes nothing; an object without formulas answers 409. (c) A sweep every `interval` (5 minutes) over every organization that compares fingerprints, nothing more: it starts a pass
   only for an object whose fingerprint differs from the one its results are current for, so a lost event is repaired and a current object costs one small read. There is **no timed rescan** of objects that are current (decision of the owner for this sprint: publish and manual start only; a sweep is easy to add if a gap is ever found).
   The state is read by `GET` on the same path (ability `metadata.view`): `NONE`, `CURRENT`, `NEEDED`, `PENDING`, `RUNNING`, `CLOSING`, the counts of the pass in flight and of the last one. There is no screen.
10. **A record that comes back from the recycle bin gets the results of today.** The recalculation reads live records only; a record that was in the bin while the formulas changed would otherwise come back with old results while its object is current. The restore refreshes the record's results the same way (`FormulaResults`), with the same
    rule (no version, no event).
11. **Edited again while it runs.** If the fingerprint of the object differs from the one the pass works towards, the pass starts again from the first record for the formulas of now; what it had done belonged to the old ones.
12. **Not built, on purpose.** Clearing the pointer of a lookup (ADR-0106) changes a record without recalculating a formula that reads it; a manual start corrects it. Typed copies of formula results (ADR-0101) stay refused: the recalculation does not maintain slots, and a formula is read without an index. A timed rescan of objects that
    are current.

## Consequences

- After a formula edit the results are untrusted at once (the fingerprint differs) and are trusted again when the pass finishes, whatever happens to events.
- The cost of a pass is one read of every live record of the object plus one update of each record whose result changed; it is proportional to the object, runs in batches, and takes no lock that a write waits for.
- An organization with a very large object sees a formula field unavailable in filters and orders for the length of the pass (ADR-0114), which the state endpoint tells.
- Settings: `platform.data.recalculation.enabled`, `batch-size`, `batches-per-pass`, `interval`, `closing-delay`.

## Alternatives considered

- **A version of the formulas stored with every record.** Exact per record, but it rewrites every record of the object even when no result changed (the churn this sprint avoids), and the completion of a pass still needs a scan.
- **A bespoke scan of the table.** A second reader of the record table that the permission, field and plan stages do not know; the query engine already pages at scale.
- **Recalculating in the transaction of the publication.** Unbounded transaction on a large object.
- **A nightly sweep over every object.** Decided against for now (above).
