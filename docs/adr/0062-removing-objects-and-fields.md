# ADR-0062: Removing objects and fields

- **Status:** Accepted
- **Date:** 2026-10-05
- **Sprint:** S10
- **Related:** [ADR-0049](0049-object-and-field-permissions.md), [ADR-0058](0058-object-and-field-definitions.md), [ADR-0061](0061-the-metadata-version-and-the-catalogue-cache.md)

## Context

A custom object or field made by mistake must be removable. Removing one must not leave permissions behind that wake up when the same name is used again,
must not leave other fields pointing at nothing, and must not lose records once records exist.

## Decision

1. **A custom object or custom field is removed by soft delete** (the row stays, ADR-0010). Standard definitions and system fields cannot be removed.
2. **The permissions on what is removed end in the same transaction.** The metadata module calls `DataPermissionCleanup` (security root, an allowed edge),
   which ends every object and field permission line on the object and its fields in profiles, access policies and individual grants, and writes one audit
   record with the count. Without this, `Holder__c` made again later under the same name would silently get the old permissions back. The security
   version rises with the change, so cached answers go at once.
3. **An object is not removed while a field points to it.** Lookup and master-detail fields of other objects (standard objects extended by the
   organization included) that name the object are listed in the refusal, `Employee__c.department__c` style; a field that points at its own object does not
   count. Remove those fields first.
4. **An object is not removed while it has records.** The seam is `ObjectUsage` (metadata root): the data module (Milestone 4) will implement it. Until then no
   bean exists and no object has records, so removal works; once it exists the same call refuses. No code change in the metadata module is needed.
5. **Removing an object removes its fields first** (the database refuses to remove an object that still has live fields), counts them for the audit record
   and ends their permissions.
6. **Dependencies on fields** (layouts, rules, formulas) do not exist yet. Sprint 11 builds the dependency graph and makes removal check it; the places to
   extend are `MetadataService.deleteObject` and `deleteField`.

## Consequences

- A name can be reused after removal and starts clean.
- The audit trail has `metadata.object.deleted` and `metadata.field.deleted` (with counts) and `access.data.permissions_ended`.
