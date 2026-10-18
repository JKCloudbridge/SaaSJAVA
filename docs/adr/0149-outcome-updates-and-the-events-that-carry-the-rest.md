# ADR-0149: Outcome updates, and the events that carry the rest

- **Status:** Accepted
- **Date:** 2026-10-18
- **Sprint:** S24
- **Related:** [ADR-0138](0138-workflow-actions-and-the-shape-of-their-settings.md), [ADR-0139](0139-background-writes-the-one-reviewed-path.md),
  [ADR-0140](0140-loop-and-recursion-protection.md), [ADR-0145](0145-the-approval-model-processes-versions-instances-and-states.md)

## Context

The architecture note lists final and reject actions (update a record, notify, create a task, start a workflow, call an integration) and its
worked example has the approval followed by more automation. Two layers keep each small and keep the approval engine from becoming a
second workflow engine.

## Decision

1. **Layer one: outcome updates**, a closed list per outcome (`APPROVED`, `REJECTED`, `RECALLED`) of fields set to **literals** (written as
   a field default is written, the same checks as `UPDATE_RECORD`: `WorkflowActionChecks` is reused). No copy of a field, no expression,
   no executable text. Every field named is an edge of the dependency graph.
2. **They are carried out by the one reviewed write door**, `BackgroundWrites`, in the **same transaction** as the decision and the
   unlock. `BackgroundWrites` is therefore reachable from `workflow` **and** `approval` (the architecture test and ADR-0139 are
   updated, not copied). Its `Source` gets a kind (`WORKFLOW` or `APPROVAL`) so the audit record and the event say which; the actor is
   the platform and no value is kept.
3. **The write runs in a savepoint** (`PROPAGATION_NESTED`, as `BulkRecords` does). `DefaultBackgroundWrites` is transactional, so an
   exception through it marks the surrounding transaction rollback-only even when the caller catches it; the savepoint is what lets the
   decision commit when the update is refused.
4. **A refused outcome update does not undo the decision and does not leave the record locked.** The instance keeps the outcome the people
   chose, it ends, the record is unlocked, the history records `outcome_update_failed` with the stable `BackgroundWriteException.Reason`
   key, and `outcome_failure` on the instance holds the same key so the inbox and the record panel of administrators show it. Refusing the
   decision instead would deadlock: the record is locked and nobody could fix what the rule rejects. **Reapplying** is an explicit,
   audited act of a member with `approvals.manage` (`POST /approvals/instances/{id}/outcome`): it carries the record's version it read,
   writes the same updates, and is refused while a newer instance is pending or when the outcome has already been applied.
5. **Layer two: everything else is a workflow.** A new trigger kind **`APPROVAL_OUTCOME`** (the process name, an outcome filter) starts a
   workflow from the events `approval.approved|rejected|recalled`. The events carry identifiers and the outcome only. Approval does
   **not** run workflow actions itself: there is no `approval -> workflow` edge, so no cycle with *start approval* (ADR-0150).
7. **The chain follows the people.** A request started by a workflow stores that workflow's chain (root, depth, workflows) on the
   instance; the outcome update's event and the `approval.*` events carry it. A workflow that starts an approval whose outcome update
   triggers the same workflow is therefore stopped by the chain rule of Sprint 23 (`loop_detected`, `depth_exceeded`) even though a person
   sat between the rounds. A request a person submitted has no chain: the first workflow it triggers starts one.
8. **Event volume.** About three outbox events per approval: `approval.submitted`, the outcome event, and the `record.updated` of the
   outcome update. Step changes are audit and history only. Negligible against the D3 thresholds; no benchmark mode is added.
