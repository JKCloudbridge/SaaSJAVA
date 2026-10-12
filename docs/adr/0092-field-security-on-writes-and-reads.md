# ADR-0092: Field security on writes and on reads

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S15
- **Related:** [ADR-0049](0049-object-and-field-permissions.md), [ADR-0050](0050-the-permission-decision-api.md), [ADR-0053](0053-the-security-cache-and-its-version.md), [ADR-0091](0091-the-write-pipeline-and-its-order.md)

## Context

Sprint 8 built `Decisions.can(member, object, field, action)` and the bulk form `accessTo(member, object)`; nothing used the field permissions until now. A field permission is explicit (a union of the profile, the access policies and
individual grants, no deny, "edit implies read"): a member who may read an object but was given no field permission reads and writes no field. Architecture note 4 (sections 21 and 22): the backend must compare incoming fields with field
permissions and never accept the whole payload blindly, and a read must filter what the frontend receives.

## Decision

1. **One question per request.** `FieldSecurity.accessOf` asks `Decisions.accessTo` once (cached since Sprint 9) and the answer (`ObjectAccess`: the readable and the editable field keys) serves the whole request, a list of 200 records
   included. There is never one question per field or per record.
2. **A field the member may not read does not exist for them.** On a write it gets the answer of a field that is not there ("This is not a field of the object."), on a read it is **absent** (the key is left out; it is not replaced by a
   placeholder, and a member cannot tell "no value" from "may not read"). Order matters: the field permission step runs *before* the step that says a system, calculated or retired field "cannot be written", so that answer cannot
   reveal a field the member may not read.
3. **A write that names a field the member may read but not edit is refused whole** ("You may not change this field."): nothing is saved, the field is named (they can see it exists). This holds for a value, for `clear` and for a
   null. A member cannot change a field by any path (create, update, clear, null).
4. **Problems about a field the member may not read are not named.** A required field missing on a create, a rule whose error field, or a formula that cannot be calculated, all report such a field under `fields` (the record as a
   whole) with a message that does not name it.
5. **Reads filter everything handed back:** the single read, the list, the recycle bin list, and also what create, update, restore and delete hand back. The stored record is complete; the member sees the part they may read. A member who
   may read an object but no field of it gets records with no values (their identifiers, versions and dates are still there).
6. **A formula field is readable only if the member may read the formula field itself; its inputs need not be readable.** This is the common behaviour of the products this platform learns from and it is what makes a formula a
   deliberate way to share a derived value (a margin without the cost and the price), but it also means a formula can reveal what its inputs hide (`salary * 12`). Whoever sets up access gives a formula field to a member
   deliberately. Calculation always uses the stored inputs, whatever the member who saves may read.
7. **A default and a calculated value apply whatever the member may edit.** The defaults of a field and the results of formulas and auto-numbers are the organization's choice and the platform's work, not the member's input.
8. **Required fields the member cannot edit.** An update is not blocked by a required field the member may not edit and did not touch (they could never fix it); a create is.

## Consequences

- Granting an object permission without field permissions gives a member an object they cannot see into. The permission screens (Sprint 8) show the matrix; the sprint documents say so plainly. A new field is invisible to every
  member except the holders of "everything" (the administrator profile) until someone grants it.
- The field permission step and the read filter are one class, so write and read cannot drift apart. `FieldSecurityMatrixIT` holds both for every one of the 19 types (a member who may read and edit, read only, neither).
