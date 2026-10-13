# ADR-0095: Formulas are calculated and stored on write, and what happens when a field's setup changes

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S15
- **Related:** [ADR-0077](0077-formula-fields-are-checked-and-are-edges-of-the-graph.md), [ADR-0080](0080-the-metadata-runtime-compiled-artefacts-and-the-publish-event.md), [ADR-0091](0091-the-write-pipeline-and-its-order.md), [ADR-0094](0094-validation-rules-on-save.md)

## Decision

1. **Calculated on every create and every update**, in the order `MetadataResolver.formulas` gives (a formula that uses another formula comes after it; each result is put into the values before the next is calculated), over the
   values the record will have after the write. The inputs are the stored values whatever the member may read (ADR-0092).
2. **The result is stored in the payload** under the formula field's API name, in the stored shape of its result type (ADR-0093), so that reading is cheap and Sprint 16 can filter and index on it. A formula with no value for this
   record (an input is missing) stores nothing and clears an old result. An update stores only the results that changed (the audit record names only those).
3. **Nobody writes a formula result:** the key is refused ("This field cannot be written.", ADR-0092). A formula result over 38 digits, or a text over the size limit, is a failure like a division by zero.
4. **A formula that cannot be calculated for these values refuses the save** and names the formula field ("This field's formula could not be calculated from these values."). That is the person's data (a zero in a divisor),
   so, unlike a broken rule (ADR-0094), it is not reported as an incident.
5. **No clock in a formula** (ADR-0077, decision of Sprint 13, confirmed for this sprint): a stored result must not go stale by itself. A "computed when read" mode, which would allow `TODAY()` in a formula, is a later decision with
   real use.
6. **Text comparison stays exact** (`=` is case-sensitive); a person writes `LOWER(x) = 'abc'` (Sprint 13 question 1, closed).

## When the setup of a field changes after records exist

Nothing is rewritten when a definition changes. Every record keeps reading as it was written. The **next write of a record is judged by the setup of that moment**:

| Change | What happens to existing records | On their next write |
|--------|----------------------------------|---------------------|
| shorter text, fewer digits, fewer decimals | untouched | the changed field is judged only if the write names it; a value the write does not name is not re-judged |
| a picklist value switched off | untouched; the value still reads | accepted where the record holds it, refused for any other record |
| a field made required | untouched; records without a value read as before | the record is refused until it has a value (a member who may not edit the field is not blocked, ADR-0092 point 8) |
| a field removed from a record type | untouched | the value stays; clearing it is allowed, writing it is refused |
| a formula edited | the stored result stays what it was | **recalculated on the next write of that record** (all formulas of the object are recalculated on every write) |
| a validation rule added or changed | untouched | the record is judged by the rule on its next write |

A formula's stored result is therefore **lazily** brought up to date. A **recalculation job per object** (so that a filter on a formula sees new results for records nobody writes) was built in **Sprint 16A** ([ADR-0113](0113-recalculation-of-stored-formula-results.md), [ADR-0114](0114-formula-fields-in-filters-and-orders.md)); `RecordsSetupChangeIT` holds the table: "records written before the change keep reading". Until the pass has finished a filter or an order on a formula field is refused.

## Consequences

- A person who edits a formula sees the old results on records until each is saved again. The object manager and the sprint documents say so.
- Because a write recalculates every formula of the object, the cost grows with the number of formulas (bounded: depth 5, the engine's bounds).
