# ADR-0182: The organization's own list of values for a picklist the platform defines

- **Status:** Accepted (the product owner approved the FA2A plan and its recommendations on 2026-10-21)
- **Date:** 2026-10-21
- **Sprint:** FA2A (Functional track)
- **Related:** [ADR-0177](0177-dependent-picklists.md) (section 3 asked for this and left the place open), [ADR-0059](0059-standard-metadata-in-definition-files.md) (a platform field is a file), [ADR-0061](0061-the-metadata-version-and-the-catalogue-cache.md) (the snapshot and its version), [ADR-0064](0064-record-types.md), [ADR-0066](0066-change-sets-and-the-publication-of-metadata.md), [ADR-0067](0067-releases-history-and-rollback.md), [ADR-0181](0181-the-solution-framework.md)

## Context

[ADR-0177](0177-dependent-picklists.md) section 3 decided that a shipped picklist may be flagged `organizationValues`: the platform owns the field, the organization's administrator owns its **list of values and its pairs**. It said this is done "through the object manager, as a change like any other". But a platform field is a **file**: it has no row, and the metadata snapshot of an organization's own field is built from
`field_definition` rows. The ADR also said "no new migration and no new table", which was true for a field an organization makes and is not true for this. A second need came from the product owner on 2026-10-21: the people who fill in a template must be able to **add a new abbreviation** (a process or a customer) from the template form, and the first number under it is 1.

## Decision

### 1. An overlay table, merged into the snapshot

A small tenant-scoped table **`organization_field_values`** (V061): one live row per organization, object and field, holding the same JSON a picklist's configuration is stored as (`{"values": [...], "dependency": {...}}`), or `{}` for "use the platform's starting list". It has forced row level security, the guard triggers, a tenant-first partial unique index and the **metadata-version trigger**, so it feeds no permission answer (no security-version trigger) and changes the metadata version exactly like the organization's own fields.
`MetadataCatalogue.build()` reads the rows once and replaces the configuration of a platform field flagged `organizationValues` with the row's. **The merged field lives and dies with the metadata version** (ADR-0061): the same cache, the same warming, nothing new to invalidate.
**The version of such a field is `row version + 1`** (0 while the organization never changed it), so a caller sends the version it read and the first change is version 1; a reset keeps its row, so the number only ever goes up.

### 2. Changed through the ordinary field update, restricted to the list and the pairs

The administrator's change is the **existing `UPDATE_FIELD` change** (no new change kind, no change to the change-kind check constraint): on a platform field flagged `organizationValues`, `MetadataService.updateField` hands the request to `OrganizationValues.update`, which accepts it only if everything the platform owns (label, description, required, unique, default, the flags) is sent back as it is (otherwise the same refusal as for any platform definition: `FORBIDDEN`, audited), checks the values and pairs with the same `FieldRules` as for any picklist, and writes the row. Because it is the existing change, **change sets, releases, the undo of a release and the screens work unchanged**, and the dependency graph judges the result.
A list equal to the platform's starting list is stored as `{}` ("use the platform's list"), so a rollback that restores it goes back to the file.

### 3. A value is never removed, only switched off

The rule every picklist has (a value that exists cannot be removed, ADR-0060) holds here too, and it has a consequence worth knowing: **the undo of "a value was added" cannot be applied** (it would remove the value), so a rollback of such a release is refused with the usual words. Switching a value off or on, changing a label and changing pairs are all undone by a rollback.

### 4. Short codes (`codeValues`)

A value that is a part of a number must be a short code. The attribute `codeValues: true` on a picklist field makes every value `[A-Za-z0-9_-]{1,10}`; the loader checks the starting list, `OrganizationValues` checks every change and addition, and the message names the setting and never the value. A text field that a format reads needs no flag (the number is refused if a value is not a code at the moment it is made, [ADR-0184](0184-number-series-declaration-formats-and-counters.md)).

### 5. Adding a value from the form

The people who fill in a record may add a value, with the ability **`field-values.add`** (new; the Organization administrator profile has it with every ability) **and** the right to edit the field **and** create or update on the object:
`POST /api/v1/metadata/objects/{object}/fields/{field}/values` with the value, an optional label and, for a dependent picklist, the values of the other field it goes with (at least one, each an active value that exists). It **only adds**: a value that exists is never renamed, switched off or removed this way; a value that is switched off is refused with a message (an administrator switches it on); adding what is there already changes nothing and is not an error; a short code is kept in capitals.
It is a direct write under the lock of the row (two members adding at once queue), takes effect at once and raises the metadata version; it is **not a release**, so a rollback does not undo it (and it could not: ADR 0182 section 3). It is audited by counts (`metadata.fieldvalues.added`: whether the value was new and how many pairs it added), never by the value. `FormFieldView` carries `canAddValues` and `codeValues` so the screen draws the control only for a member who may; the API decides again.
A member who may not read the controlling field is told nothing about the pairs (the form omits them), so they cannot choose where a new value goes and the call is refused with "Choose the value of the other field this one goes with"; an administrator does it in the object manager.

### 6. What the loader and the baseline say

`organizationValues` is allowed on a single-value picklist of an object the record store holds; a dependent picklist whose controlling picklist is organization-owned must be organization-owned too (or the organization could add a controlling value and never pair it). `StandardMetadataBaselineTest` records the attribute and the controlling field (`organizationValues dependsOn=category`) and **not the values**: the starting list is data a release may replace (the product owner's real list replaces the placeholder as a data-only change); once an organization has its own row, a replaced starting list no longer reaches it.

## Consequences

- Migration **V061** (expand only), `OrganizationValuesStore`, `OrganizationValues`, the merge in `MetadataCatalogue`, the ability `field-values.add`, the endpoint above, and the audit events `metadata.fieldvalues.changed` and `metadata.fieldvalues.added`.
- A new organization starts with the platform's list and a row appears only when the organization first changes it. A platform release that changes the starting list reaches organizations that have never changed it; for the others the organization's list is theirs.
- Tests: `OrganizationValuesIT` (another organization never sees a change, a value only switched off, rollback of a switch-off and the refusal of a rollback that would lose a value, the cache moves, the ability and the right to edit are both needed, a forged header, the audit holds counts), `FieldRulesDependencyTest`, `StandardMetadataLoaderTest`, the isolation harness lists `organization_field_values`.
