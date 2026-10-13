# ADR-0114: Formula fields in filters and orders

- **Status:** Accepted
- **Date:** 2026-10-13
- **Sprint:** S16A
- **Related:** [ADR-0095](0095-formulas-stored-on-write.md), [ADR-0100](0100-the-query-model-and-its-stages.md) (point 8), [ADR-0105](0105-filters-and-orders-without-an-index-on-large-objects.md), [ADR-0113](0113-recalculation-of-stored-formula-results.md)

## Context

ADR-0100 point 8 refused a formula field in a filter and in an order, because the stored result is not current for records nobody has saved since the formula changed, and said that Sprint 16A lifts the refusal together with the recalculation. The sprint has to decide what a filter or an order on a formula does while a recalculation of that
object is in flight, so that an answer is never made from results that belong to an older formula.

## Decision

1. **A formula field is a field like another in a filter and an order**, compared as the type of its result (a number as a number, a text exactly, a day, a time, true or false), read from the stored result. It follows every rule of the unindexed path (ADR-0103, ADR-0105): allowed up to `unindexed-scan-limit` live records, refused above with the reason that
   it cannot be indexed ("add a condition on an indexed field"). A formula whose result is a time of day cannot be compared (as a time of day cannot be elsewhere).
2. **Never trust a result older than the formula's current version.** The trust is judged from the fingerprint of the formulas (`FormulaStamp`) and the state of the recalculation of the object (`FormulaCurrency`): the stored results are trusted only when the object is at rest and the fingerprint they were calculated for is the fingerprint of the formulas now.
   Otherwise a filter or an order on a formula field of that object is refused with `VALIDATION_ERROR` at the place of the field: "The results of this object's formulas are being brought up to date, so a formula field cannot be used in a filter or an order for a short while. Try again in a few minutes." The refusal starts at the moment the formula is published (the
   fingerprints differ; no event is needed) and ends when the pass has finished. A query that does not name a formula field is not affected, and the state is asked at most once per query, only when it names one.
3. **Why refuse, and not the two alternatives of the sprint prompt.** *Degrading to an unindexed path while the object is "recalculating"* has nothing to degrade to: a formula has no index (ADR-0101: the recalculation rewrites results without maintaining typed copies), so its filter is unindexed already. *A version stored with every result* would rewrite every
   record of the object when the pass finishes, whether or not its result changed, which is the churn ADR-0113 avoids, and would make a filter exclude the records not yet recalculated (a wrong answer that looks right). A refusal with the reason is the only answer that is never wrong.
4. **Reading a record is not refused.** A single read and a list show the stored result, which may belong to the older formula until the pass has reached the record; the state endpoint says whether they are current. (A form is a person looking at one record, who can see the result is old; a filter hides the very records it does not match.)
5. **The record view is unchanged.** The generated client and the API description say so; the object manager shows the state later (no screen in this sprint).

## Consequences

- An organization that edits a formula on a very large object loses filters and orders on formula fields for the length of the pass; every other question works.
- A formula field can finally be used in a list's filter and sort, at the cost of the unindexed path.
- The wording is the API's own; the screens present it unchanged (the standing rule of the frontend).
