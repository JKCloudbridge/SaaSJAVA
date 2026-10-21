# ADR-0169: Who made and who last changed a definition

- **Status:** Accepted
- **Date:** 2026-10-20
- **Sprint:** UA3 (UI Alignment track, not a numbered sprint)
- **Related:** ADR-0010 (base schema conventions), ADR-0058 to ADR-0062 (objects and fields), ADR-0069 to ADR-0075 (layouts, record pages),
  ADR-0129 (`ReferenceLabels`), ADR-0168 (the Home screens in the list and detail pattern)

## Context

The approved design shows "Created by" and "Modified by" on the list of page layouts and on the page of a field. ADR-0168 left them out because
no view of the API returned them. The user asked (2026-10-20) for them to be shown, as long as it is done properly and documented.

Two different things are called "created by" and must not be mixed up:

- **A record's** creator and last editor are the standard system fields `createdById`, `createdAt`, `updatedById` and `updatedAt`
  (`metadata/standard/_system-fields.yml`). Every object has them and the platform fills them in when a record is written. Nothing was needed.
- **A definition's** creator and last editor (who made this layout, who last changed this field) are the base columns `created_by`, `created_at`,
  `updated_by` and `updated_at` that every table carries (ADR-0010). The rows already hold them, set to the actor of the change. Only the API
  never returned them.

## Decision

1. **One new read endpoint, nothing else changes.** `GET /api/v1/metadata/objects/{object}/authorship` (ability `metadata.view`, organization hosts)
   returns a list of `AuthorshipView` (`kind`, `apiName`, `createdBy`, `createdAt`, `updatedBy`, `updatedAt`) for the object itself (when it is the
   organization's own), its fields, record types, page layouts, record pages and validation rules. No existing view, constructor or table changed, so
   no existing caller or test is affected; the change is additive and backward compatible. No migration: the columns exist.
2. **Names, not identifiers.** `createdBy` and `updatedBy` are display names. They are resolved in one batch through the shared-kernel contract
   `ReferenceLabels` for `User` (the identity module answers: **active members of the organization only**, display name only). The platform's own
   actor (the all-zero identifier) is shown as `Platform`. A person who has left, or an identifier that names nobody, gets **no name** (the field
   is absent) and the answer does not say why, exactly as the lookups of ADR-0129. The answer never carries an identifier or an address.
3. **The platform's standard definitions have no entry.** They are files, not rows (ADR-0058), so there is nobody to name. The screens say
   `Platform` for a definition that is not the organization's own, and a dash where a name cannot be shown.
4. **One statement, constant text.** The service (`Authorship`, internal to `metadata`) reads the six tables of the module with one fixed `union all`
   statement; the object name is a parameter, no table or column name is ever built from a request. It reads only the module's own tables
   (row level security applies as for every read).
5. **A convenience of the screens, never a gate.** The browser asks for it beside the object and draws a dash when the answer is slow, refused or
   missing. Nothing waits for it and a failure never reaches the person.

### What is deliberately not done

- The authorship of applications, tabs, workflows, approval processes and notification texts (their screens are not part of Object settings); the
  endpoint takes an object, and those definitions have none. They can get their own list when a screen needs it.
- A history of who changed what over time. That is the audit trail (ADR-0054, ADR-0056), which already records every change by actor; this is only
  "who made it and who touched it last".
- A person's name is a display name that changes when the person changes it: the screen shows the name of now, not the name at the time.

## Consequences

- The frontend shows the two columns on the list of page layouts and on the pages of a field, a record type, a validation rule and the object.
- The OpenAPI document and the generated client carry `AuthorshipView` and `getObjectAuthorship`.
- Tests: `AuthorshipIT` (kinds listed, the right person named, no identifier or address in the answer, another organization's object does not
  exist, a standard object has no entry), the endpoint added to the allowed/denied/cross-tenant/forged-header/platform-administrator/unauthenticated
  matrix of `LifecycleAccessIT`; frontend `authorship.test.tsx`.
