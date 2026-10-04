# ADR-0050: The permission decision API

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S8
- **Related:** [ADR-0040](0040-effective-permissions-and-the-read-contract.md), [ADR-0049](0049-object-and-field-permissions.md); delivery plan S8, S14, S15, S17

## Decision

`Decisions` (security root) is the one place that combines object and field permissions; the data engine calls it from Sprint 14.

1. **Shape.** `can(membershipId, object, action[, record])` and `can(membershipId, object, field, fieldAction[, record])` return a `Decision`
   (allowed, plus an internal `Reason`). The bulk forms `accessTo(member, object)` and `accessToAll(member)` give the object actions and the
   readable and editable fields in one answer, for lists and forms.
2. **The tenant is never a parameter.** It is the organization of the thread's context (host plus authenticated membership). A member who is
   not in it is nobody: the same "no" as a member with nothing.
3. **Algorithm.** The object must exist in the catalogue (for a field, the field too); the member's effective permissions must allow the
   action; for a field they must also allow the field action, with read on the object for reading and create or update for editing.
4. **Uniform answers.** An unknown object, an unknown field, an object the member may not use, a deactivated member, a member of another
   organization and a platform person (no membership) all answer "not allowed". The reason is for callers' tests and logs, never for a
   response. A platform role gives nothing here.
5. **The record is accepted and ignored** until Sprint 17, so callers can pass it now. View-all and modify-all will start to apply there.
6. **No cache** (Sprint 9 decides invalidation); every question reads the current rows inside the caller's transaction. The abilities read is
   cheaper than the data read, so actions that only ask an ability do not load matrices.
7. **Presentation endpoints** (`/data-access/mine`, `/members/{id}/access`) show the same answer to screens; they decide nothing.

## Tests

`DecisionMatrixTest` (1,678 table-driven cases), `DataAccessPropertyTest`, `DataAccessIT`; each checked to fail under a quick mutation.
