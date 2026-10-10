# ADR-0072: The dependency graph learns layouts, record pages, assignments and applications

- **Status:** Accepted
- **Date:** 2026-10-11
- **Sprint:** S12
- **Related:** [ADR-0065](0065-the-dependency-graph-and-pre-publish-validation.md), [ADR-0067](0067-releases-history-and-rollback.md), [ADR-0069](0069-layouts-record-pages-applications-and-where-they-live.md)

## Context

[ADR-0065](0065-the-dependency-graph-and-pre-publish-validation.md) built the graph with one contributor per kind of dependent and said that layouts add theirs. The exit criterion of Sprint 11 (a field
removed while a layout shows it is refused with a precise error) could only be proved with a record type then; this sprint proves it with the layout itself.

## Decision

1. **New kinds of node**: layout, record page, application, assignment (the last two only depend, nothing needs them). Their words in a refusal are the same style as before:
   `page layout Details__c of Deal__c shows field Deal__c.stage__c, which does not exist after this change.`
2. **Five contributors** (`DefinitionDependencies`), one per dependent, nothing else in the lifecycle changes:
   | Dependent | Needs |
   |-----------|-------|
   | page layout | its object; every field it shows |
   | record type | the layout it uses (`layout`) |
   | record page | its object; its layout; every field named by its header and by its visibility rules; for each related list the related object, the field it follows and every column |
   | layout assignment | its object; its layout; its record type if it has one |
   | application | every object and every record page its navigation opens |
3. **Two rules, not edges.** (a) A layout must show every **required** field and master-detail field of its object, and not hide it: a record needs a value there, so a form without the field could
   never save a valid record (the same rule record types follow, [ADR-0064](0064-record-types.md)). System fields are exempt. (b) A related list must follow a **lookup or master-detail field of the
   related object that points to the page's object**; anything else is refused with the field named.
4. **Live and in a change set.** A change made at once and the check of a change set run the same graph after applying the changes, so the refusal is the same in both. A group of changes may fix a
   dependency again (remove the field and the layout line together); the order of the changes in a set does not matter because the graph is read once, at the end.
5. **Rollback restores all of it.** Every new kind has its undo computed from the before-image (a creation is undone by a removal, a change by the old values on top of the version it left, a removal
   by a creation). The undo of a release is ordered recreations first, then restorations, then removals, so a field that was removed together with the layout line that showed it comes back before the
   layout is put back. `DefinitionLifecycleIT` proves it for every kind.
6. **Not an edge: the profile of an assignment or an application** ([ADR-0071](0071-which-layout-a-person-gets.md)). A profile belongs to `security`, and deleting a profile must never be blocked.

## Consequences

- Removing an object now also fails while a layout, record page, assignment or application names it; the message names the first dependent and counts the rest.
- Each check of a change set costs one more pass over the snapshot (every definition is already in it). The cost note of [ADR-0066](0066-change-sets-and-the-publication-of-metadata.md) (a check costs as much as a
  publication) stands; the schema check runs before the rest, inside each change.
