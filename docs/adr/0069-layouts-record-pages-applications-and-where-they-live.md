# ADR-0069: Page layouts, record pages, applications and layout assignments, and where they live

- **Status:** Accepted
- **Date:** 2026-10-11
- **Sprint:** S12
- **Related:** [ADR-0058](0058-object-and-field-definitions.md), [ADR-0064](0064-record-types.md), [ADR-0066](0066-change-sets-and-the-publication-of-metadata.md), [ADR-0001](0001-modular-monolith.md)

## Context

Architecture note 3 (sections 11 to 15) asks for a `PageLayout` (sections, fields, read or edit behaviour), a separate `RecordPage` (components, tabs, related lists,
conditional visibility, actions), an `Application` with navigation that the browser does not hardcode, and (delivery plan S12) a layout per record type and profile. They are
**definitions**: records do not exist before Milestone 4, and the rendering with real records comes in Sprints 19 and 20.

## Decision

1. **The definitions live in the `metadata` module**, not in `application`. Reason: the lifecycle (change sets, releases, the undo of a release, the dependency graph) is internal to
   `metadata`, and `application` is planned to depend on `metadata` (`docs/modules.md`). Definitions in `application` would need `metadata` to call back into it, which is a cycle that
   the architecture tests forbid. So a layout is published, checked and rolled back by the very code that does it for a field. No new dependency edge for `metadata`.
2. **The `application` module is the runtime side**: it answers "which applications may this member use and what does their menu show?" ([ADR-0073](0073-applications-navigation-and-the-members-menu.md)).
   It depends on `metadata`, `security` and `tenant`, as `docs/modules.md` already said.
3. **Four tenant-scoped tables (V034)**, all published metadata, with the base columns, the row guard, row level security enabled and forced, a partial unique index on the
   name (`where deleted_at is null`) and the metadata version trigger:
   `page_layout`, `record_page`, `application` and `layout_assignment`. The definition itself (sections, components, navigation) is one `jsonb` column checked by a JSON schema
   ([ADR-0070](0070-definitions-are-json-checked-by-a-schema.md)); the navigation items belong to the application row because they are always read and changed with it.
4. **Names.** A layout, record page or application has a label and a permanent API name that ends with `__c` (the person types `Details`, the platform stores `Details__c`), as for objects,
   fields and record types ([ADR-0058](0058-object-and-field-definitions.md)). A layout and a record page belong to one object and are unique per object; an application belongs to the
   organization. Objects whose records belong to the platform (`managedBy` set) have none of them, as for record types.
5. **Identity never changes** (API name, object, profile, record type): a database guard refuses it, and a definition of a custom object must name a live custom object of the same
   organization.
6. **Differences from the written story text (flagged).** As in [ADR-0066](0066-change-sets-and-the-publication-of-metadata.md): the live tables hold **published metadata only**, so there is no
   `status` column on `Application` (note 3, section 14) and no "Version 1, 2, 3" per layout (section 20); drafts are rows of the change set tables and the history is the numbered
   releases. `developerName` is the API name.
7. **Deleting an object is refused while a layout, record page, assignment or application still needs it**, with the dependent named (unlike record types, which belong to their object and
   go with it). The person removes the layouts first, or does both in one change set.

## Consequences

- Everything new in metadata goes through `MetadataLifecycle`: twelve new kinds of change (`CREATE_`, `UPDATE_`, `DELETE_` for `LAYOUT`, `RECORD_PAGE`, `APPLICATION`, `LAYOUT_ASSIGNMENT`), each with its
  undo computed from the before-image.
- `metadata_change` learns the new kinds, an empty object name (an application) and a longer item name (an assignment key): V034 widens three checks; nothing is removed.
