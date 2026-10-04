# ADR-0049: Object and field permissions: keys, containers, union, implications, no deny

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S8
- **Related:** [ADR-0039](0039-abilities-profiles-access-policies-and-individual-grants.md), [ADR-0040](0040-effective-permissions-and-the-read-contract.md),
  [ADR-0050](0050-the-permission-decision-api.md); architecture note 2 sections 19 and 20; delivery plan S8, S10, S15, S17

## Context

Objects, fields and records do not exist before Milestones 3 and 4. Permissions on them must be stored and combined now so the data engine
can ask later without a redesign.

## Decision

1. **An object is named by a key** (`object-a`), **a field by `<object key>.<field key>`**. The security module owns no objects: a contract,
   `ObjectCatalog` (security root), says which exist for the organization of the context. Sprint 10's metadata module provides the real
   implementation (an allowed edge: metadata depends on security); until then a configured stand-in lists sample objects for the
   local profile and the tests, and none in a deployment. A permission can only be given for what the catalogue knows; a stored key that
   no longer exists is ignored, never an error.
2. **Same three containers, same union** (ADR-0040): profile (only while licensed), access policy (also through groups; a licence-bound one only
   while licensed) and individual grant. Tables `object_permission` and `field_permission` (V026), tenant-scoped, one row per container and key
   with an action list; a guard keeps every row inside its organization and requires an active member for a grant.
3. **Object actions:** read, create, update, delete, view-all, modify-all. **Implications** (applied after the union, so order never matters):
   create, update, delete, view-all imply read; modify-all implies all six. **View-all and modify-all are stored and shown now and change
   nothing else until record-level security (Sprint 17)**, which must make them bypass record rules (carried in the delivery plan).
4. **Field actions:** read, edit (edit implies read). A field permission works only with its object: reading needs read on the object,
   editing needs create or update. A field the member may not read is, for them, a field that does not exist.
5. **Default is no access.** A new object or field gives nothing to anyone except the administrator profile, which holds everything (a flag
   in the calculator, not a list: `DataAccess.fullAccess()`).
6. **No deny rule.** A union is the same in every order and grouping, which the property tests rely on. An organization hides a field by not
   giving it, using a smaller profile or policy. Revisit only with data, with the precedence written first.
7. **Who edits:** `access.manage`, no new ability. Anti-escalation (ADR-0039 point 7) extends: someone who may invite but not manage access can only create
   a member with a profile whose abilities **and** object and field permissions they hold themselves (`DataAccess.covers`).
8. **Replacing a matrix** replaces everything of that container; lines not listed end. Counts of lines, never names, go to the audit record.
9. **Ending:** removing a profile or policy ends its rows; ending a membership ends the member's grants.
