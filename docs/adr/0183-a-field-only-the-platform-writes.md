# ADR-0183: A field only the platform writes

- **Status:** Accepted (the product owner approved the FA2A plan and its recommendations on 2026-10-21)
- **Date:** 2026-10-21
- **Sprint:** FA2A (Functional track)
- **Related:** [ADR-0176](0176-number-series-and-the-first-approvers-number.md) (the number is written by the platform only), [ADR-0092](0092-field-security-on-writes-and-reads.md), [ADR-0139](0139-background-writes-the-one-reviewed-path.md), [ADR-0160](0160-before-save-automation.md), [ADR-0138](0138-workflow-actions-and-the-shape-of-their-settings.md)

## Context

[ADR-0176](0176-number-series-and-the-first-approvers-number.md) says no field permission grants edit on the `number` field. But the Organization administrator profile has **full access**: `Decisions` gives it edit on every field, and `WritePipeline` excluded only system, calculated and retired fields, so an administrator could type a number. A number is the one thing that must come from the counter.

## Decision

A generic field attribute **`platformWritten: true`** in the object file (a text field that is not required and has no default: a member could never fill a required one in; the loader checks):

- **Member writes treat it like a calculated field.** `FieldSecurity.checkWrite` refuses it for every member, **including the administrator and a member with every right**, in the same words as a calculated field ("This field cannot be written.") whether a value is set or cleared, through the API, a bulk change and an import (all go through `Records` and the one pipeline).
- **`BackgroundWrites` may write it** (the platform's own write, no member: `RecordGate.Passed.byPlatform()`); that is how the engine of a later sprint will write a number after taking it from the series.
- **A definition of an organization cannot set it either.** A workflow action, an approval outcome and a before-save automation are written by an administrator; `WorkflowActionChecks.settable` and `BeforeSave` leave the field out, so a number cannot be set through a definition.
- **The form describes it as not editable** (`ObjectForms`), so the screen draws no control; the person reads the number.
- **Reads are unchanged**: a member who may read the field reads it.

The baseline records the attribute (`platformWritten`); dropping it from a released field fails the build.

## Consequences

- Tested by `NumberSeriesIT` (the administrator cannot set, change or clear the number through create, patch, clear or bulk; the platform can), `PlatformWrittenImportIT` (an import row that names it is reported, the others are saved), `StandardMetadataLoaderTest` (the loader's rules) and the form description.
- It is generic: any later cluster that needs a number or a stamp only the platform may write declares it in its object file.
- Not covered by this attribute, on purpose: the database. The unique field that holds the number is the backstop against a duplicate ([ADR-0111](0111-uniqueness-of-field-values.md)); a person with the database owner's rights can write anything, as for every table.
