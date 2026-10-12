# ADR-0067: Releases, history and rollback

- **Status:** Accepted
- **Date:** 2026-10-10
- **Sprint:** S11
- **Related:** [ADR-0066](0066-change-sets-and-the-publication-of-metadata.md), [ADR-0062](0062-removing-objects-and-fields.md); architecture note 3 sections 20 and 33

## Decision

1. **Every publication is a release** (`metadata_release`, numbered 1, 2, 3 ... per organization, append-only): a change set, a **change made at once** (one change; "quick"), or
   a rollback. It stores what it added, changed or removed (`summary`, shown in the history), **the changes that undo it** (`undo`) and the metadata version after it.
   A live change that changes nothing makes no release. A database guard keeps a release from being changed or removed, except that a later rollback marks it once.
2. **The undo is computed before a change is made**, from the before-image: a created thing is removed; an update restores the old values on top of the new version; a removed
   field is made again with all its settings; a removed object is made again with its custom fields and record types; a default record type that was moved is given back.
3. **Rollback applies the latest release's undo as a new release** (kind ROLLBACK, `undoes_release`); the undone release is marked `rolled_back_by`. **Only the latest** can be rolled back;
   rolling back a rollback redoes it (its own undo). A history that rewrote itself would not be history.
4. **A rollback is refused, with the reason, when it would lose data or break something:** the undo is applied by the same rules and checked by the dependency graph
   ([ADR-0065](0065-the-dependency-graph-and-pre-publish-validation.md)); in addition `ObjectUsage` ([ADR-0062](0062-removing-objects-and-fields.md)) is asked, now with `hasValues(object, field)` next to `hasRecords(object)`, whether
   removing a field or object would lose values or records (kind RECORDS). No bean implements it before Milestone 4, so nothing is blocked today; a stand-in proves the refusal in `RollbackWithRecordsIT`.
   This is the user's answer to question 3: allowed while no records depend on what was added since.
5. **A removal comes back as a definition only.** The permissions on a removed field or object ended when it was removed ([ADR-0062](0062-removing-objects-and-fields.md) point 2, so that a reused name starts clean); a rollback does
   not restore them. The history page says so.
6. **Check before doing:** `POST /releases/latest/rollback-check` rehearses a rollback (rolled back) and answers like a change set check.

## Consequences

- The history is bounded to the latest 200 releases in the API; the table keeps all.
- Rollback to an older release is "roll back the latest, repeatedly", one release at a time.