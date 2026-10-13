# ADR-0112: The pair of parents of a junction is unique

- **Status:** Accepted
- **Date:** 2026-10-13
- **Sprint:** S16A
- **Related:** [ADR-0106](0106-record-links-and-delete-behaviours.md) (point 9), [ADR-0111](0111-uniqueness-of-field-values.md)

## Context

An object with exactly two master-detail fields is a many-to-many association between its two parents (ADR-0106). Sprint 16 kept "one record per pair" with a lock the write pipeline takes on the pair and said that a database constraint would replace it in Sprint 16A. The sprint prompt of 16A does not mention it; the
documents of Sprint 16 (ADR-0106, `Sprint 16.md`) promise it, and the work is the same mechanism as the uniqueness of a field, so it is built here.

## Decision

1. **The pair is a constraint like a unique field.** Both master-detail fields of a junction are always served by link slots (ADR-0101). A pair of link slots has a bit and a static partial unique index of its own (V043: bits 22 to 27, one per pair of the four link slots, indexes `object_record_u_p_ref_<a>_ref_<b>`), with the
   same mask, the same states (`WAITING`, `ENFORCING`, `CONFLICT`, `ENFORCED`) and the same background check-in of the existing records as ADR-0111. A row of `unique_enforcement` has the kind `PAIR`, the first master as its field and the second as its partner; masters are ordered by their API names, as everywhere else.
2. **It starts when both copies are complete**, under the lock of the object, like any constraint.
3. **The answer is the one Sprint 16 gave:** `VALIDATION_ERROR` at the second master, "These two records are already linked." It names neither record. The unique index is the backstop for two writes at the same moment on two instances and gives the same answer.
4. **The lock stays until the database takes over.** While no pair constraint exists for the object, the pipeline still takes its transaction lock on the pair (ADR-0106), so the window of ADR-0111 point 7 does not lower what Sprint 16 guaranteed. Once the constraint exists (any state) the stage "unique" asks the storage instead
   and the lock is not taken.
5. **The recycle bin does not hold a pair; a restore that would link a pair twice is refused** (409, the words of ADR-0111 point 5, "or links the same two records"). Moving a detail to another master onto an existing pair is refused like a create.
6. **Existing duplicates.** Junction records made before the check existed can hold a pair twice. The check-in finds them: the constraint goes to `CONFLICT` with the count of records that share a pair, new pairs are still refused, and the constraint completes when the data is fixed. There is no publication step to refuse, because a pair is not
   something an administrator switches on.

## Consequences

- One constraint per junction; an object cannot have more than two master-detail fields, so there is never more than one pair per object.
- The pair is read from the link slots, so a junction needs both of its link slots complete before it is enforced; the index status shows the pair as `WAITING` until then.
