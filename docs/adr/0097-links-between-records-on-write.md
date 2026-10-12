# ADR-0097: Links between records on write (lookup and master-detail)

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S15
- **Related:** [ADR-0063](0063-relationships.md), [ADR-0084](0084-the-record-store.md), [ADR-0091](0091-the-write-pipeline-and-its-order.md)

## Decision

1. **A value written to a lookup or master-detail field is the identifier of a record of the target object** (`ReferenceConfiguration.targetObject`), and the record must exist, be live and be in the same organization. A record in the
   recycle bin, a record of another object, a record of another organization and an identifier that matches nothing all get the **same answer** ("The linked record does not exist."): nobody learns what exists elsewhere.
   The check is one question per target object per write (`StorageStrategy.liveIds`), however many fields point at it, and it runs under the organization's row level security as well as the explicit filter.
2. **Only a change is checked.** A link the write does not change is not re-checked: a record whose target was removed since (what removing a target does is Sprint 16) can still be saved.
3. **A master-detail is always required** (ADR-0060) and its master does not change unless the field is `reparentable`: moving a detail to another master is refused otherwise ("The master of this record cannot be changed."). Clearing
   a master-detail is a missing required value.
4. **No delete behaviours.** `CLEAR`, `REFUSE` and `CASCADE` (ADR-0063) are carried out by the query engine of Sprint 16, with the index of who points at whom; this sprint only refuses a bad link on write.
5. **Targets a platform module owns are refused** (Sprint 15; since Sprint 15A a `User` is answered by the reference directory of [ADR-0099](0099-the-reference-directory-links-to-objects-a-platform-module-owns.md) and the others are still refused). A lookup to a user, a profile, an access policy, a role, a licence type or a record type (objects with `managedBy`) points at rows that live in another module's tables; the record
   store may not read them, and the module graph does not allow `data` to ask. Writing a value to such a field is refused with "Links to this kind of object are not available yet." rather than accepted unchecked. Sprint 16 (lookup
   resolution) decides how: a membership/user directory contract is the likely answer. A field of that kind that is left empty works as before.
6. **Uniqueness is not enforced** (a unique field can be written twice). It needs an index on a typed slot, which is the index policy of Sprint 16; the API description and the sprint documents say so.

## Consequences

- The lookup to `User` that many CRM models use (an owner, a manager) is the one visible gap of this sprint; it is recorded as a Sprint 16 item in the delivery plan.
