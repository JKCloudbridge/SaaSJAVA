# ADR-0145: The approval model: processes, versions, instances and states

- **Status:** Accepted
- **Date:** 2026-10-18
- **Sprint:** S24
- **Related:** [ADR-0136](0136-workflow-definitions-versions-and-lifecycle.md), [ADR-0066](0066-change-sets-and-the-publication-of-metadata.md),
  [ADR-0091](0091-the-write-pipeline-and-its-order.md), [ADR-0089](0089-what-a-record-may-write-and-what-it-may-never-leak.md)

## Context

Sprints 22 and 23 made automation run, retry and replay, but everything they do is done by the platform on its own. Some decisions are a
person's: an expense above a limit needs a manager and then finance. The architecture notes (6, sections 15 to 19 and 44) ask for a
configured process (entry criteria, ordered steps, approvers, final actions), for delegation as its own model and not an edit of the
process, and say that **no one state model is forced on every object**: the lifecycle comes from metadata.

## Decision

1. **A process belongs to one object.** It has an API name ending in `__c`, a label, a description, an `active` flag and the number of the
   definition in force. It is published metadata (the same lifecycle as a workflow: three change kinds with undo, a dependency
   contributor, a schema, a release item, the limits as settings). A process may be edited only through that lifecycle.
2. **A definition is immutable and numbered** (`approval_process_version`). A new row only when the entry criteria, the steps, the
   outcome updates, the fallback or the self-approval rule change; a new label or switching on and off is a change of the process row.
   **An instance is pinned to the version it started under**, so editing a process never changes a pending approval.
3. **A definition holds**: an optional **entry criteria** (an expression of the engine with its own purpose `APPROVAL_ENTRY`, true when the
   record may be submitted; none means any record), an ordered list of **steps** (a name, a label, the approver rules, the completion rule
   `ANY` or `ALL`), **outcome updates** for `APPROVED`, `REJECTED` and `RECALLED` (closed data: fields set to literals, ADR-0149), an optional
   **fallback approver** (a user or a public group, ADR-0146) and whether a submitter **may approve their own request**. A rejection always
   ends the instance (ADR-0147). Nothing in a definition is run as code; `approval` does not depend on `expression` (it receives a
   `CompiledApprovalProcess` from `MetadataResolver`, as `workflow` and `data` do).
4. **Instances.** `approval_instance` is one request for one record under one process version. **At most one instance of a record is
   `PENDING`** (a partial unique index); a record may have any number of past ones. States, a closed list:

   | State | Meaning |
   |-------|---------|
   | `PENDING` | a step is waiting for a decision; the record is locked (ADR-0148) |
   | `APPROVED` | the last step was approved |
   | `REJECTED` | an assignee rejected a step |
   | `RECALLED` | the submitter, or an administrator, withdrew it |
   | `CANCELLED` | an administrator who holds `approvals.override-lock` ended it. **Only this**: the loss of a plan feature or the switching off of a process never cancels (ADR-0152) |

   A refusal to start (no process fits, the criteria are not met, nobody to ask, the feature is off, the record is already pending) is an
   error to the caller or a stable reason of a workflow step, never a state.
5. **Step instances** (`WAITING`, `ACTIVE`, `APPROVED`, `REJECTED`, `SKIPPED`) and **assignments** (`OPEN`, `APPROVED`, `REJECTED`,
   `REASSIGNED`, `CLOSED`) are closed lists too; exactly one step of a pending instance is `ACTIVE`. A property test holds the state machine
   to that: no sequence of decisions leaves a step both approved and rejected, or an instance `PENDING` with no active step.
6. **`attention`.** A pending instance whose active step has nobody who can decide (every assignee left, was deactivated or lost the licence,
   or the rule resolves to nobody at the moment the step becomes active, or a pinned definition names something that no longer resolves)
   stays `PENDING`, its step stays `ACTIVE` with no open assignment, and `attention` holds a stable reason (`no_approver`,
   `approver_unresolvable`). It is shown in the inbox of administrators and on the record; a person with `approvals.manage` assigns somebody
   (ADR-0147) or cancels. **No state is added for it.** The first step is different: if it resolves to nobody the **submit is refused** with
   `no_approver`, nothing is created and nothing is locked.
7. **The approval state is kept by the platform in its own tables.** An object may show it in a field of its own choosing because the
   outcome updates set that field (ADR-0149); the platform does not add a status field to any object, and a process does not need one.
8. **Which process a submit uses.** The caller may name a process of the object; otherwise the **only active process whose entry criteria
   the record meets** is used. No process fits is `no_process_applies`; more than one fits is a refusal that names the processes by label so
   that the person chooses. The criteria are judged on the record as it is when the lock row is taken (ADR-0148), not as it was when the
   screen was drawn.
9. **Limits (settings)**: `platform.metadata.limits.max-approval-processes-per-object` (10), `max-approval-versions` (200),
   `max-approval-steps` (10), `max-approvers-per-step` (10), `max-approval-outcome-assignments` (20).

## Consequences

- Editing a process in the middle of an approval is safe by construction; the old version stays while an instance names it (a database
  foreign key, as for workflows), and a process cannot be removed while one of its instances is pending (ADR-0152).
- `CANCELLED` is a deliberate act of a named person and is audited; nothing makes an instance disappear.
- The model is one process = one straight list of steps. Conditional routing, parallel branches and send-back are not in the model
  (ADR-0153); they would be a new definition kind, not a change of this one.
