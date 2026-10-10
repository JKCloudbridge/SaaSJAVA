# ADR-0066: Change sets and the publication of metadata (draft, check, preview, publish)

- **Status:** Accepted
- **Date:** 2026-10-10
- **Sprint:** S11
- **Related:** [ADR-0061](0061-the-metadata-version-and-the-catalogue-cache.md), [ADR-0053](0053-the-security-cache-and-its-version.md), [ADR-0065](0065-the-dependency-graph-and-pre-publish-validation.md); architecture note 3 sections 20 to 22 and 33

## Context

Until Sprint 10 every metadata change was live at once. The architecture asks for draft, validate, preview, publish, active, new version and rollback, and for the
publication of a dependent set to be atomic. The note shows a `status` column on the definitions; the user answered the plan on 2026-10-10 (all recommendations).

## Decision

1. **Drafts are not rows of the live tables.** `object_definition`, `field_definition` and `record_type` hold **published metadata only**; there is **no `status`
   column** on them (a row there is by definition published, so the column would always say the same, and drafts as second rows would change every query, unique
   index and guard). This differs from the note's wording and is recorded in the delivery plan.
2. **A change set is a draft** (`metadata_change_set`, status DRAFT, PUBLISHED or DISCARDED) holding an ordered list of **changes** (`metadata_change`): nine kinds
   (create, update, delete of object, field, record type), each carrying the JSON of **the very request record the live endpoint takes**. Changes are added or
   taken out only while the set is a draft (a database guard); a published or discarded set never changes again. Up to 200 changes per set, 50 open sets.
3. **Check, preview and publish are the same act** (`MetadataLifecycle`): apply the changes in order **by the same services the live endpoints call**
   (`ChangeApplier` → `MetadataService`, `RecordTypeService`), each inside a savepoint so one failure does not hide the next; then check the dependency graph of the
   result ([ADR-0065](0065-the-dependency-graph-and-pre-publish-validation.md)). Then:
   - **check** (`validate`) and **preview** roll the transaction back (`Rehearsed`, thrown from the work): nothing stays, **there is no second copy of the rules**.
     The report lists every problem (kind RULE, DEPENDENCY, CONFLICT or RECORDS, the change it comes from, what depends on it) and what would be added, changed or removed;
     a preview adds the affected objects as they would be. Preview in this sprint is the catalogue as it would be; page layouts to render arrive in Sprint 12.
   - **publish** commits only if there is no problem; otherwise everything is rolled back (`Refused`) and the answer is a CONFLICT with every problem. **All or nothing.**
   An update inside a set carries the version read when it was drafted, so a set overtaken by someone else's change reports a CONFLICT for that change.
4. **Cache and security version (the question of the sprint).** Only published metadata is cached and read by the runtime: a draft never reaches the catalogue snapshot,
   the metadata version or the security version, because it lives in other tables and rehearsal rolls back. A publication is one transaction; the triggers of V031 and V033
   raise the metadata version and the security version for every row it changes, and other instances see the change at the next question after the commit ([ADR-0061](0061-the-metadata-version-and-the-catalogue-cache.md)).
   A transaction that applies changes bypasses the cache and reads its own changes (`MetadataVersions.wrote()`), as in Sprint 10.
5. **Audit of every step:** `metadata.changeset.created|changed|checked|discarded`, `metadata.release.published`, `metadata.publish.refused` (written after the rolled-back transaction),
   always by identifiers, kinds, counts and API names, never by a name, label or description typed by a person.
6. **One publication at a time per organization:** a transaction advisory lock keyed by the organization is taken first (`LifecycleStore.lockPublications`), so releases are numbered
   without gaps and two publications cannot interleave.

## Consequences

- A rehearsal costs as much as a publication (it runs the same SQL); limited by the size of a set. It is the price of not keeping a second rule engine.
- Standard definitions (files) are untouched; a change set that touches one is reported in words (the platform defines it).