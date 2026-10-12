# ADR-0098: Record type assignments: which profiles may use which record type

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S15A
- **Related:** [ADR-0064](0064-record-types.md), [ADR-0071](0071-which-layout-a-person-gets.md), [ADR-0072](0072-the-dependency-graph-learns-layouts-pages-and-applications.md), [ADR-0093](0093-type-validation-and-record-types-on-records.md), [ADR-0091](0091-the-write-pipeline-and-its-order.md)

## Context

Sprint 15 enforced that a record type exists, is in use, offers its fields and allows its picklist values (ADR-0093), but any member who may create in an object could choose any type of it, because the metadata had no link between a
record type and a profile. ADR-0093 recorded the design and left it for a side sprint. Sprint 12's layout assignment answers a different question (*which layout* a profile sees for a type); this one is *whether a profile may use the type at all*.

## Decision

1. **A new kind of published metadata, `record_type_assignment`** (migration V038, tenant-scoped, version-triggered like `layout_assignment`). One row is "this profile may use this record type of this object"; `is_default` marks the type a
   new record of that profile gets when it names none. At most one row per object, record type and profile, and at most one default per object and profile (partial unique indexes, tenant first). The definition guard of V034 keeps the
   object, record type and profile of a row from ever changing.
2. **The rule: a record type with no assignment is open to every profile; with at least one it is open only to the profiles named.** Nothing built before this sprint changes behaviour, and the first assignment of a type is what closes it.
   The administrator's profile is a profile like any other: once a type is restricted, an administrator who is not named cannot create records of it.
3. **It changes only through the lifecycle** (ADR-0066): three change kinds (`CREATE_`, `UPDATE_`, `DELETE_RECORD_TYPE_ASSIGNMENT`) with their undo, a dependency contributor, and the same `applyNow` for the live endpoints. The key of an assignment is
   the record type, a tilde and the profile (`Renewal__c~<profile>`). A change set may create a type and its assignments together; a publication that assigns a type nobody makes is refused whole. **The three kinds had to be added to the database check constraint
   that lists every valid `metadata_change.kind`** (`metadata_change_kind_known`, V039), the same way every earlier sprint that added a kind did (V035); CI found this missing on the first run, fixed the same day.
4. **The dependency edges:** an assignment *belongs to* its object and *applies to* its record type, so a record type (or an object) that still has assignments cannot be removed and the refusal names them. The profile is only an identifier
   (ADR-0071): **deleting a profile is never blocked** by an assignment. The assignment of a deleted profile stays visible without a name so an administrator can remove it, and it **still restricts** the type (it fails closed: a
   restriction that silently dissolved when a profile was deleted would open a type to everyone).
5. **The default of a profile.** Making an assignment the default takes the mark off the profile's previous default of the object, in the same transaction; the undo of that change puts the mark back (the same `giveDefaultBack` approach as
   the default type of an object). A default must be a type that is in use. A type that is later switched off simply stops being given (the next rule decides).
6. **What a new record gets when it names no type** (`Metadata.defaultRecordTypeFor`): the type marked as the default of the member's profile if it is in use; otherwise the default type of the object if it is in use **and the profile may use
   it**; otherwise none. The object's default is never given to a profile that may not use it.
7. **What a write does** (the record-type stage of the pipeline, ADR-0091): a type the profile may not use answers **exactly like a type that does not exist** ("This object has no such record type."), so nobody learns what is restricted. A
   record keeps its type when its profile loses it (nothing is rewritten): naming the type it already has changes nothing, and any other update leaves it; only *moving* a record to a type the profile may not use is refused. **Reading is never
   restricted by an assignment** (record-level visibility is Sprint 17).
8. **Reading the assignments** (`metadata.view`): `GET /api/v1/metadata/objects/{o}/record-type-assignments` (all of an object) and `.../record-types/{rt}/profiles[/{profile}]`; changing needs `metadata.manage` and `metadata.publish`, as
   for every live change (ADR-0068). The limit is `platform.metadata.limits.max-record-type-assignments-per-object` (500).
9. **No screen before Sprint 19.** The object manager's record-type editor shows the assignments when it is built; the change-set screens already show the three new kinds in words.

## Consequences

- The profile of a member is read once per write, and only for an object that has record types (`MemberAccess.profileOf`); an object without types pays nothing.
- A restricted default can leave a profile with no usable type at all (if every type is restricted to other profiles): such a member creates records with no type. An administrator sees this in the assignment list.
- An organization that wants "every profile except one" lists the profiles it wants; there is no deny rule (the platform has none anywhere, ADR-0040).

## Alternatives considered

- **A list of profiles on the record type itself:** fewer files, but a per-profile default needs a second structure, the existing record type change kinds and their undo would carry it, and the record type request would grow for a feature
  most organizations never use. Rejected: it also mixes two lifecycles (the type and who may use it).
- **An assignment that dissolves when its profile is deleted:** opens a restricted type to everyone silently. Rejected (point 4).
- **Letting administrators bypass the restriction:** a back door that would have to be explained in every audit. Rejected; an administrator who needs a type assigns it to their own profile, which is one audited change.
