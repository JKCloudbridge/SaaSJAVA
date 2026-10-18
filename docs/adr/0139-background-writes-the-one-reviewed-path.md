# ADR-0139: How a workflow writes a record: `BackgroundWrites`, the one reviewed path

- **Status:** Accepted
- **Date:** 2026-10-17
- **Sprint:** S23
- **Related:** [ADR-0089](0089-what-a-record-may-write-and-what-it-may-never-leak.md), [ADR-0091](0091-the-write-pipeline-and-its-order.md),
  [ADR-0122](0122-bulk-record-operations.md), [ADR-0123](0123-csv-import-jobs.md),
  [ADR-0125](0125-record-level-security-on-writes-links-and-uniqueness.md), [ADR-0138](0138-workflow-actions-and-the-shape-of-their-settings.md)

## Context

`BackgroundRecords` (Sprint 22) is read-only and says so: it was written so that the first action that changes a record adds the write
deliberately, in one reviewed place. Records are written through `Records` and nowhere else (ADR-0122, ADR-0123): the write pipeline,
the owner index, the shares, the search index, the links, the typed copies, the audit record and the `record.*` events. A second,
private path would drift from the first the first time a stage is added.

## Decision

1. **`BackgroundWrites` is a narrow public interface of the `data` module** (`create`, `update`), implemented by the same class family
   as `Records` and running **the same `WritePipeline`** (shape, record type, field permission rules for what can be written at all,
   type, required, relationship, unique, rule, formula), the same storage, `RecordLinks.sync`, `SearchIndexer`, the typed copies and
   the unique mask, `RecordChanges` for audit and event. There is one pipeline, with one switch: *who is asking*.
2. **What the platform skips is only what is about who.** The member gate, the object permission, field permission (the platform may
   edit every field the object has that people can write at all: calculated, system, auto-number and retired fields stay unwritable
   and give a stable reason), the record permission stage (ADR-0125) and the visibility of a linked record (the target need only be
   a live record of the target object). The platform still obeys **what is true of every record**: shape, type, required, record
   type, relationship, uniqueness, validation rules and formulas.
3. **The actor is the platform.** `created_by`/`updated_by` carry the platform's system identity; the audit record carries no user and
   its `source` is `SYSTEM` with the attributes `workflow` (API name) and `execution` (identifier); the event carries the chain
   ([ADR-0140](0140-loop-and-recursion-protection.md)). Names and identifiers only, never a value.
4. **No owner, share or version changes on its own account.** An update bumps the record's version as every update does (that is not
   new). The owner of a created record is the owner of the triggering record (a record the workflow creates for the people who own its
   cause); no share is made; a record that has no owner (never happens for a record made by a person) gets the platform identity.
5. **Reachable only from `workflow`** (an architecture test, `WorkflowArchitectureTest`, names the allowed callers). The `data` module
   publishes the interface; no controller, import or bulk path uses it.
6. **A refusal is a stable reason key and never a value.** `BackgroundWriteException` carries a `Reason` and nothing else (no message
   from the pipeline, no field name that is not shaped like a field, no value, no rule message):

   | Reason | Stage or cause | Retried? |
   |--------|----------------|----------|
   | `invalid_value` | shape or type | no |
   | `field_not_writable` | field permission stage (not a field, calculated, system, retired) | no |
   | `record_type_rejected` | record type | no |
   | `required_missing` | required | no |
   | `link_target_gone` | relationship | no |
   | `unique_clash` | unique (and the database's backstop) | no |
   | `rejected_by_rule` | a validation rule rejected | no |
   | `formula_failed` | a formula could not be evaluated | no |
   | `record_gone` | the record was removed for good or is not in the object | no |
   | `record_in_bin` | the record is in the recycle bin (read-only, ADR-0084) | no |
   | `object_unavailable` | the object does not exist or is held by a platform module | no |
   | `concurrent_modification` | somebody changed the record between the read and the write | **yes** |

   *Permanent* means the same attempt would fail the same way until a person changes data or the definition, so retrying wastes the
   attempts and delays the dead letter that a person must see. A concurrent modification is the one transient reason of the data
   module; the transient classes of the database are classified from the exception type by the workflow module
   ([ADR-0142](0142-retry-policy-and-backoff.md)).
7. **The differential test** holds the two doors together: the same write made by an administrator through `Records` and by the
   platform through `BackgroundWrites` ends in the same stored values and the same validation outcome (stage by stage).

## Consequences

- The pipeline gains an "acting as the platform" mode (`RecordGate.Passed` with no member); every place that asked the member for
  something (record type profile, field access, visibility of links, the user of auto-numbers) has a platform answer. A new write
  stage is automatically in both doors.
- `BackgroundRecords` stays read-only; its Javadoc points to this interface.
