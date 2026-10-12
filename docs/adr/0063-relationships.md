# ADR-0063: Relationships

- **Status:** Accepted
- **Date:** 2026-10-10
- **Sprint:** S11
- **Related:** [ADR-0058](0058-object-and-field-definitions.md), [ADR-0060](0060-field-types-and-their-configuration.md), [ADR-0062](0062-removing-objects-and-fields.md); architecture note 3 sections 8 and 9; delivery plan S11

## Context

Sprint 10 built lookup and master-detail fields that store the object they point to. Nothing yet said what happens to a record that points at another
when that other goes, whether a detail may move to another master, how the child list shows on the parent, or how many-to-many is made. Architecture note 3
(section 8) sketches a `RelationshipDefinition` with source and target objects, fields, a type and a cascade rule.

## Decision

1. **A relationship is read from the fields; it has no table of its own.** One source of truth: the lookup or master-detail field. The metadata module
   answers `GET /api/v1/metadata/objects/{object}/relationships` with three lists, computed from the published catalogue (`Relationships`):
   - *parents*: the lookup and master-detail fields of the object (many-to-one; **one-to-one** when the field is unique);
   - *children*: the fields of other objects that point at it (one-to-many; one-to-one when unique), shown as lists on the object with a label;
   - *many-to-many*: an object with **exactly two master-detail fields** is a junction; its two masters are related through it (`AccessPolicyAssignment`
     is one). Self-references and the three system lookups (owner, created by, changed by) are handled: the system ones are not business relationships and are left out.
   The note's `sourceField`, `targetField`, `cascadeBehavior` and `required` are exactly the field, the target, the delete behaviour and the required flag.
2. **Lookup and master-detail become unique-capable.** Unique on a reference makes it one-to-one (no other change to the type table; `FieldType`).
3. **What happens when the parent is removed** is a setting of the reference, stored in its configuration (`ReferenceConfiguration.onDelete`, a closed set,
   `DeleteBehaviour`):
   - master-detail: always `CASCADE` (the detail is removed with its master); not chosen, refused if sent;
   - lookup: `CLEAR` (the pointer is emptied, the record stays; **the default**) or `REFUSE` (the parent cannot be removed while records point at it);
   - a **required lookup** cannot clear a value it must have: unset it refuses, an explicit `CLEAR` is a validation error;
   - the user's third option, "keep" (the pointer stays and points at nothing), was **not built**: it leaves broken links that every list and report would
     have to cope with (answer to question 1, recommendation accepted).
4. **Who may re-parent:** `reparentable` on a master-detail (default no): whether a detail may be moved to another master. A lookup is always free to change.
5. **Both directions visible:** `listLabel` (up to 80 characters) names the list on the parent; empty means the plural label of the child object.
6. **The rules are stored and shown now, carried out by the data engine.** No records exist before Milestone 4, so cascade, clear, refuse and re-parent are
   enforced in Sprint 14 and 15 by reading these settings; until then the object manager shows them and the dependency graph ([ADR-0065](0065-the-dependency-graph-and-pre-publish-validation.md))
   knows the edges. A stored row from before Sprint 11 has only the target; reading it fills the behaviour the type has by default (a test proves it).
7. The target of a reference still never changes ([ADR-0060](0060-field-types-and-their-configuration.md)); the behaviour and the label may.

## Consequences

- Migration needs none: the settings live in the existing `config` JSON; nothing is renamed.
- `FieldSettings` gains `onDelete`, `reparentable` and `listLabel` (the contract, OpenAPI and the TypeScript client were regenerated).
- Sprint 14 reads `ReferenceConfiguration`; it must not invent a second place for these rules.