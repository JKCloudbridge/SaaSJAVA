# ADR-0136: Workflow definitions, their versions and their lifecycle

- **Status:** Accepted
- **Date:** 2026-10-16
- **Sprint:** S22
- **Related:** ADR-0063 to ADR-0068 (change sets, releases, rollback), ADR-0065 (the dependency graph), ADR-0076 to ADR-0081
  (the expression engine and the metadata runtime), ADR-0078 (validation rules, the model for a condition),
  [ADR-0132](0132-admin-screens-builders-and-state-conventions.md) (the change set is the only draft mechanism),
  [ADR-0133](0133-event-processing-stays-on-the-outbox-decision-d3-final.md),
  [ADR-0134](0134-the-scheduler-of-timed-workflows-decision-d9.md), [ADR-0137](0137-executions-asynchronous-after-commit-and-what-they-keep.md)

## Context

A workflow is automation as data: when something happens to a record (or at a time), if a condition is true, run actions
(architecture notes, Phase 6, sections 10 to 14, 22 to 24). It must follow the lifecycle every definition follows (draft, check,
publish, version, roll back), name the fields and objects it needs so that removing one is refused with the workflow named, and
it must be versioned so that an edit never changes a run under way (note 6, section 22).

## Decision

### Where the definition lives

1. **The definition is metadata, so it lives in the `metadata` module**, not in `workflow`. The machinery of change sets
   (`ChangeApplier`, `ChangeCodec`, `DependencyGraph`, the snapshot) is internal to `metadata`, and `metadata` cannot depend on
   `workflow` without a cycle. `workflow` reads definitions through `Metadata.workflows()` and
   `MetadataResolver.workflows(object)`, and runs them. The new tables are `workflow` and `workflow_version` (V053).
2. **A workflow belongs to an object** (a validation rule's shape): identity is the object and the API name (ending `__c`), and
   it never changes (the same identity guard as the other definitions). The records of an object the platform owns cannot have
   a workflow. At most 50 workflows per object (`platform.metadata.limits.max-workflows-per-object`).
3. **A closed, typed model, no executable text.** A trigger (`RECORD_CREATED`, `RECORD_UPDATED`, `RECORD_DELETED`,
   `FIELD_CHANGED`, `EVERY_DAY`, `EVERY_WEEK`, `DATE_TIME`, `DAYS_AFTER_DATE`), an optional condition and a list of actions.
   The request is the schema: bean validation bounds every part, `WorkflowRules` accepts only the properties a kind uses, a real
   time zone, a real time, and the actions of the closed list `WorkflowActionTypes` with the settings that type takes (an entry
   there plus one executor class is all a new type needs). **Triggers of external systems are not accepted** until the
   integration milestones: no inert placeholder.
4. **The condition is an expression of the engine** with the new purpose `WORKFLOW_CONDITION` (it must give true or false and may
   use `TODAY()` and `NOW()`, as a validation rule may, ADR-0078). It is checked in two places: the grammar when the workflow is
   saved, and the fields and types after the change by the dependency graph, so the order of the changes of one set does not
   matter. The compiled form is kept with the organization's metadata snapshot like the compiled rules (ADR-0080), so judging a
   record costs no parsing. **`workflow` does not depend on `expression`**: it receives `CompiledWorkflow`, which judges a record,
   exactly as `data` receives `CompiledRule` (the precedent of Sprint 15). The edge the plan allowed is not added.

### Versions

5. **A `workflow` row is the identity** (label, description, active, the number of the definition new executions use) and
   **`workflow_version` rows are immutable definitions** (the trigger, the condition, the actions). A new version is written
   **only when the trigger, the condition or the actions changed**; a change of the label, the description or only whether the
   workflow is active writes none (the database refuses an update of a version row). The numbers are 1, 2, 3, ...; at most
   200 (`max-workflow-versions`), and a workflow at the limit is told to make a new workflow.
6. **An execution names the version it was created under** (foreign key, `workflow_execution.workflow_version_id`). Publishing a
   new version while executions are queued changes nothing for them: they run the old version. A version that an execution still
   names is never removed; old versions that nothing names are removed a week after they were replaced by a clean-up that lets
   the foreign key decide (`platform_workflow_version_cleanup`, run per organization), so no module reads another module's table.
7. **Switching a workflow on or off is its own small change** (`UPDATE_WORKFLOW` that keeps the definition as it is; the endpoint
   `.../activation`): a release of one change, no new version. A switched-off workflow starts no new execution; the queued ones
   end as skipped with the reason `workflow_inactive` when they are claimed (the administrator's expectation of "off").
8. **Removing a workflow** is a soft delete; its queued executions end as cancelled with the reason `workflow_removed`; finished
   ones stay in the history until the retention. The undo of the release makes it again **as a new workflow** (version 1): the
   executions of the old one keep their own history.

### Lifecycle

9. **Draft, check, publish and rollback go through the Sprint 11 lifecycle**, with three new change kinds (`CREATE_WORKFLOW`,
   `UPDATE_WORKFLOW`, `DELETE_WORKFLOW`), each with its undo, a release item `WORKFLOW`, a dependency contributor
   (`WorkflowDependencies`) and a case in `ChangeApplier` and `ChangeCodec`. The edges: a workflow belongs to its object, starts
   when its fields change, counts days from its date field (which must be a date or a date and time), uses the fields of its
   condition and selects records by the fields of its selection. A violation names the workflow and the thing that is missing.
   A rollback that would break a dependency is refused with the reason, as for every definition.
10. **The selection of a scheduled workflow** (which records it considers) is the filter of the record query model
    (`QueryFilterNode`), checked by the data module's own stages through the optional contract `RecordSelectionChecker`; bounded
    to 50 nodes and 4 levels.

### Who may

11. **Abilities** `workflows.view` (read definitions and executions) and `workflows.manage` (create, change, switch, remove, try a
    condition). Making a change live also needs `metadata.publish`, as every definition does (ADR-0068). Because a change in a
    change set asks only for the metadata abilities, **whoever applies a workflow change (checks, previews, publishes, rolls
    back) needs `workflows.manage`**, enforced where the change is applied.
12. **The plan feature is `workflows`**, which the licensing catalogue has had since Sprint 6 (the first plan includes it). The
    plan said `automation`; a second feature for the same thing would be a duplicate. A workflow is made active, or changed
    while active, only when the plan has the feature (else a CONFLICT); a run of an organization without it is skipped with the
    reason `feature_off`. Switching off and removing are never refused for the plan. Licence, permission and feature stay three
    separate mechanisms.
13. **The metadata edge `metadata -> licensing`** is added for the question "does the plan have workflows" (licensing depends on
    `tenant` only, so there is no cycle).

## Consequences

- A workflow is published, checked and rolled back like every other definition; nothing about the lifecycle changed.
- A run under way is never changed by an edit; the history says which version ran.
- The old definition of a run stays readable until the retention and the clean-up remove it.
- Known limit: a record page assignment could not be put into a change set before this sprint (V052 added the kinds to the code
  and not to the check constraint of `metadata_change`); V053 widens the constraint to all kinds, including those.

## Alternatives considered

- **The workflow tables in `workflow`:** the change-set machinery could not reach them without a cycle.
- **A new version on every save:** a rename would fork history for nothing.
- **A second draft mechanism for workflows:** ADR-0132 forbids it.
- **A free expression for the selection:** the closed query model already exists, is checked and is parameterized.

## References

- `platform-app/src/main/java/app/platform/metadata/internal/` (`WorkflowService`, `WorkflowRules`, `WorkflowStore`,
  `WorkflowCodec`, `WorkflowDependencies`, `WorkflowController`, `WorkflowVersionCleanup`, `ChangeApplier`, `ChangeCodec`)
- `platform-api-contract/src/main/java/app/platformapi/` (`WorkflowTriggerSpec`, `WorkflowActionSpec`, `WorkflowActionTypes`,
  `CreateWorkflowRequest`, `UpdateWorkflowRequest`, `WorkflowView`)
