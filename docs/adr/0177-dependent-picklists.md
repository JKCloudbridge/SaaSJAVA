# ADR-0177: Dependent picklists

- **Status:** Accepted (the product owner, 2026-10-21; built in FA2A; section 3 is carried out by [ADR-0182](0182-the-organizations-own-list-of-a-platform-picklist.md), which also corrects the statement below that no table is needed)
- **Date:** 2026-10-21
- **Sprint:** FA0 (Functional track, spike; no product code)
- **Related:** [ADR-0058](0058-object-and-field-definitions.md) (field model), [ADR-0064](0064-record-types.md) (record types restrict picklist values), [ADR-0063](0063-relationships.md), [ADR-0091](0091-the-write-pipeline-and-its-order.md), [ADR-0129](0129-record-screens-the-members-description-names-and-validation.md), [ADR-0176](0176-number-series-and-the-first-approvers-number.md)

## Context

A template carries a **category** and a **sub-category**; the sub-categories that may be chosen depend on the category, and the pair builds the first two parts of the number (`PL/DD/0001`). The platform has picklists (a list of stored values, labels and an active flag) and record types that restrict a picklist's values,
but **no field depends on another** (nothing in the code mentions a controlling field). This is a generic capability the next cluster will use as well.

## Decision

### 1. The model

A **single-value picklist** may name a **controlling field** and a **matrix** of the values it allows for each value of the controlling field. It is part of the field's configuration (the JSON `ConfigCodec` reads and writes), so it needs **no new table and no new change kind**:

```
PicklistConfiguration { values: [...], dependency: { controllingField: "category", allowed: { "PL": ["DD", "QA"], "PM": ["DD"] } } }
```

- The controlling field is a **single-value picklist of the same object**, and is **not itself dependent** (depth one in the first release; a chain is a later release). Every key of the matrix is a value of the controlling field (active or not); every listed value is a value of this field (active or not).
  A controlling value with an empty list is allowed (it means "nothing goes with this").
- Limits (settings): at most 200 controlling values and **5,000 pairs** per field (`platform.metadata.limits.max-dependency-pairs`); the whole configuration stays within the field-configuration size already limited.
- Stored values are the stable keys (`PL`, `DD`); labels are for people. **The number series reads the stored values** ([ADR-0176](0176-number-series-and-the-first-approvers-number.md)), so the values of these two fields must match `[A-Za-z0-9_-]{1,10}` where a series uses them (the loader checks).

### 2. Where each rule lives (the list the prompt asked for)

| Place | What it does |
|---|---|
| `FieldRules` (`metadata/internal/FieldRules.java`) | checks the shape at save: type, controlling field exists and qualifies, no loop, keys and values exist, limits; messages by field API name, never the typed text |
| the **dependency graph** (`DependencyGraph`) | the dependent field has an edge **to** its controlling field: a controlling field cannot be deleted, retired or changed to another type while one depends on it; a loop is refused; check, preview and publish of a change set see it, in any order of the changes of one set |
| a **new pipeline stage `dependent`**, placed after `relationship` and before `unique` (`WritePipeline.STAGES`) | judges a dependent field **only when the write touches it or its controlling field**: a non-empty dependent value needs a non-empty controlling value whose row allows it. **A record that already holds an old pair is never blocked by an unrelated edit.** A refusal says "This value does not go with the chosen value of <field>." and names no value |
| record types | a record type's own subset of values still applies; the allowed set is **the intersection** of the record type's values and the matrix row (a restricted record type never widens the matrix) |
| the form descriptor (`ObjectForms`) and the control (`FieldRegistry.tsx`, `RecordForm`) | the descriptor tells the screen the controlling field and the matrix **for fields the member may read**; the control offers only the allowed values and clears a chosen value that stops being allowed when the controlling value changes. **Presentation only: the server decides again on every write.** If the member may not read the controlling field, the descriptor omits the dependency and the server still enforces it |
| import and bulk | go through `Records` and so through the pipeline: a bad pair is reported per row and does not stop the others |
| the object manager (screens) | a dependency editor on the field form: pick the controlling field, then a grid of controlling value by dependent values (a small table of checkboxes; at 5,000 pairs a filter by controlling value) |
| change kinds | the field's existing **update** change carries the new configuration and its undo is the old one (as every field change), so the lifecycle (change sets, releases, rollback) needs nothing new |

**Making an existing field dependent over existing records is allowed.** It is not refused (unlike `unique`, which can be made true for no existing record): the check of the change set reports "N records hold a pair that is no longer allowed" as a note, and the records stay as they are until someone edits one of the two fields.
This avoids a setup change that blocks a whole object's records for good.

### 3. Who defines the pairs? (a point the analysis did not cover)

The codes and departments (`PL`, `DD`, ...) are the **organization's own business data**, not the platform's. But the shipped object `InspectionTemplate` is a platform definition whose fields an organization cannot change.
**Decision: a generic attribute on a shipped picklist field, `organizationValues: true`.** The platform owns the field (name, type, that it is dependent, the controlling field); **the organization's administrator owns its list of values and the matrix** (add a value, make one inactive, change the matrix), through the object manager, as a change like any other.
The platform ships a small starting list (so the cluster works the day it is switched on) that the organization can change. A value that records hold is never removed, only made inactive. This is small (metadata attribute, loader, baseline test, the field form) and it is what makes the cluster usable without a platform release per customer.
**Confirmed by the product owner, 2026-10-21: a new organization starts with a short starting list**, which the product owner will supply (codes, sub-categories and which pairs go together); the administrator can then change it.

## Consequences

- FA2 builds: the model and `FieldRules`, the dependency edge, the `dependent` stage and its entry in `WritePipelineStagesIT`, the descriptor and control, the dependency editor, `organizationValues`, the loader and baseline changes. **Tests:** property tests over random matrices (the stage accepts exactly the pairs of the matrix, intersected with a record type); a bulk of 200 records with a mixture of pairs; an import with bad pairs; the old-pair-is-not-blocked rule; a change set that makes a field dependent and rolls back; the dependency graph (loop, removal of the controlling field); an isolation test is not needed (no new table).
- No new migration and no new endpoint **for a field an organization makes**: the matrix travels with the field's configuration (the OpenAPI document gains the configuration's new part and the generated client is regenerated). *Correction (FA2A):* section 3 (`organizationValues`) needs one small table, because a platform field has no row to hold the organization's list: V061, [ADR-0182](0182-the-organizations-own-list-of-a-platform-picklist.md). The cross-field rules (the controlling field exists and qualifies, no chain, every key and value exists) are the dependency graph's question, asked of the result of a whole change set, not `FieldRules`'s; `FieldRules` checks what the one field can tell (shape, limits, that every listed value belongs to the picklist).
- Depth one and single-value picklists only; multi-picklists, a checkbox as the controlling field and chains are not in the first release.
