# ADR-0137: Executions: asynchronous after the commit, what they keep, and what stays synchronous

- **Status:** Accepted
- **Date:** 2026-10-16
- **Sprint:** S22
- **Related:** [ADR-0016](0016-transactional-outbox-and-idempotent-consumers.md), [ADR-0125](0125-record-level-security-on-writes-links-and-uniqueness.md)
  (background work runs as the platform), ADR-0091 (the write pipeline), ADR-0012 (what is never logged),
  [ADR-0133](0133-event-processing-stays-on-the-outbox-decision-d3-final.md),
  [ADR-0135](0135-the-workflow-dispatch-system-scope.md), [ADR-0136](0136-workflow-definitions-versions-and-lifecycle.md)

## Context

Architecture note 6 (sections 12 to 14) separates what must happen before the commit (validation, authorization, critical
constraints) from what happens after it (workflow processing, notifications, integrations). Sprint 22 builds the second half as
an engine: a record change is announced, matched against the organization's workflows, queued as executions and run by workers,
each run recorded.

## Decision

### Synchronous and asynchronous, written down

1. **Everything a workflow does is asynchronous, after the commit of the change that caused it.** Validation, formulas,
   authorization, record-level security, uniqueness and every other critical constraint stay in the write pipeline of the
   request (ADR-0091); a workflow cannot reject, change or delay a save.
2. **Before-save automation** (a synchronous step inside the transaction of the write: set a field from another before it is
   stored) is **out of this sprint** and is Sprint 25's "synchronous before-save automation limits". It needs a bound on time,
   on the number of steps and on recursion that this engine has no use for. Nothing built here makes it harder: the executions
   are a separate table written by a separate transaction, the write path is untouched, and a before-save kind would be a new
   trigger kind and a hook in the pipeline, not a change of this model.

### The path of a cause

3. **The event.** `RecordChanges` writes `record.created|updated|deleted` through the outbox in the transaction of the change,
   with the object, the record identifier, the version and the **names** of the changed fields, never a value. The handler
   (`workflow.record-events`) matches the active workflows of the object (ADR-0133) and inserts **one execution per workflow
   version and event** in its own transaction. `FIELD_CHANGED` means "this field was among the changed ones"; the old value is
   not available (events carry no values); a condition on the current value tells "changed to Submitted".
4. **The key.** An execution has an idempotency key: `event:<event>:<version>` for an event, `firing:<version>:<instant>:<record>`
   for a schedule (ADR-0134). The key is unique per organization. A redelivered event, a handler run twice, a lease taken over or
   two instances racing make no second execution: the marker of the outbox (ADR-0016) is the first line, the unique key the second.
5. **The worker** is the dispatcher (ADR-0135): it claims queued executions with `SKIP LOCKED` and a lease (2 minutes), counts
   the claim, and runs each in the organization's own context. The version after the claim is the claim's token; every later
   change of the row carries it, so an instance that lost its lease cannot overwrite the outcome of the one that took over.
   A run that crashes leaves a lease that expires and the execution is claimed again, counted; at more than
   `platform.workflow.worker.max-attempts` claims (3) it is set aside as failed (`attempts_exhausted`) without being run.

### What a run does

6. **It judges at the time it runs.** The record is read as it is then (as the platform, through the query engine's own stages
   and the record store, ADR-0125), and the condition is judged on it, against the version of the workflow the execution was
   created under. The version of the record the event announced is kept on the execution for the history, not used to judge.
7. **The outcomes are stable keys, never sentences or values:**

   | Status | Reason | When |
   |--------|--------|------|
   | `COMPLETED` | none | all steps ran |
   | `SKIPPED` | `condition_false` | the condition is false or has no value |
   | `SKIPPED` | `condition_not_evaluable` | the condition cannot be evaluated for the record (a division by zero, a field that no longer exists); never silently false |
   | `SKIPPED` | `record_gone` | the record is gone, or in the recycle bin for a trigger other than `RECORD_DELETED` |
   | `SKIPPED` | `record_restored` | `RECORD_DELETED` and the record was restored meanwhile |
   | `SKIPPED` | `workflow_inactive` | the workflow was switched off after the execution was queued |
   | `SKIPPED` | `feature_off` | the organization's plan does not have workflows |
   | `SKIPPED` | `object_missing` | the object no longer exists |
   | `CANCELLED` | `workflow_removed` | the workflow was removed |
   | `CANCELLED` | `version_missing` | the version no longer exists (should not happen: a foreign key keeps it) |
   | `FAILED` | `action_failed` | a step threw; the type name of the exception is kept, never its message |
   | `FAILED` | `decision_failed` | reading the definition or the record threw |
   | `FAILED` | `attempts_exhausted` | claimed more often than the limit without ever reporting back |

   `RECORD_DELETED` reads the record from the recycle bin.
8. **Steps.** The actions run in order, **each in a transaction of its own** that renews the lease, carries the action out and
   marks the step completed: an action's database effects and its step commit together or not at all. An execution taken over
   after a crash goes on after the steps that completed and never repeats one. An effect outside the database must pass
   `executionId:position` as the idempotency key of the call (the `ActionExecutor` contract); this release has no such action.
9. **The action seam.** An action is a closed type with a schema (`WorkflowActionTypes`) and an executor behind an interface in
   `workflow`; a registry refuses to start when the two lists differ. **This release ships one action, `PUBLISH_EVENT`**: it
   announces `workflow.signal.<name>` through the outbox, naming the object, the record, the workflow, the execution and the step,
   no value. It changes no record, so it cannot start a workflow and cannot loop. Updating or creating records, mail,
   notifications, approvals and integrations are Sprints 23 to 28; loop and recursion protection comes with the first action that
   changes a record.
10. **No retry, no dead letter here.** A failed run is `FAILED` with its reason, the type name and the attempt count, and nothing
    is lost silently (a metric, a log line with the type name, an error report). Retry with backoff, dead letters and replay are
    Sprint 23.

### What an execution keeps

11. **Facts, never values.** The tables hold identifiers, the version numbers, a stable reason key, times, the attempt count and
    the type name of a failure. **Never** a record value, an expression, a condition's text, a setting, a typed message or an
    exception message. A leak test (`WorkflowFlowsLogsAreCleanIT`) drives every path with marker values and searches the logs, the
    audit records, the events and the execution rows for them. `workflow_execution` carries the names the history shows
    (workflow API name, version number) so that reading it never touches the definition tables.
12. **Tenant-scoped, forced row level security** on `workflow_execution`, `workflow_step` and `workflow_schedule` (ADR-0015); the
    dispatcher's scope is ADR-0135. The tenant of an execution is the tenant of its event (the context the handler ran in), never
    a value of the payload.
13. **Retention.** Finished executions and their steps are removed after `platform.workflow.retention.executions` (90 days,
    never fewer than 30, like the audit records), organization by organization, with one audit record of the pass
    (`retention.workflow.cleaned`, a count).
14. **Observability.** Metrics: `platform.workflow.executions{result}` (queued, completed, failed, skipped, cancelled, claim_lost),
    `platform.workflow.events{result}` (malformed, no_workflow, no_match, queued), `platform.workflow.queue.depth`,
    `platform.workflow.queue.delay` (queued to claimed), `platform.workflow.event.delay` (event to handled),
    `platform.workflow.event.handling{result}` (the cost of the handler itself, which is what threshold T6 reads),
    `platform.workflow.execution.duration`,
    `platform.workflow.schedule.firings{result}`. Spans for the handling of an event (`workflow.event`), a run
    (`workflow.execution`) and a firing (`workflow.schedule`), tagged with identifiers; the outbox does not carry the trace of the
    request, so they are separate traces joined by those tags. Audit: a record per publish, activation and removal (by name and
    counts, `metadata.workflow.*`), per firing of a schedule (`workflow.schedule.fired`) and per clean-up. An unexpected failure
    goes to the error tracking with the tenant, never the message.

## Consequences

- A save is never slower or less reliable because of a workflow.
- A workflow acts on the record as it is when it runs, so late or reordered events are harmless; the price is that a condition
  cannot see "what it was before" (stated, with the alternative of capturing old values as a separate design).
- Executions of an organization with many workflows and many writes can build a queue; the queue depth and delay are metrics,
  there is no fair share between organizations yet (oldest first), and a per-organization rate limit of automation is part of the
  hardening in Sprint 25.
- A deactivated or removed workflow stops its queued executions at once, which is what an administrator expects.

## Alternatives considered

- **Running the actions inside the event handler:** the outbox transaction would hold an action for as long as it runs, retries
  would re-run completed steps, and a separate queue of executions is what Sprint 23 needs to add retry and replay.
- **Capturing old values for "changed from X to Y":** touches every write path and is a design of its own; only if a requirement
  names it.
- **One transaction for all steps:** a failure would undo steps that already announced events to nobody yet, but an external
  action in Sprint 23 cannot be undone that way; one transaction per step is the shape that carries over.
- **A table of status history per execution:** the steps and the final status say what happened; the audit trail has the changes
  of the definitions.

## References

- `platform-app/src/main/java/app/platform/workflow/internal/` (`WorkflowEventHandler`, `Dispatcher`, `DispatchStore`,
  `ExecutionRunner`, `ExecutionStore`, `ActionExecutor`, `ActionRegistry`, `PublishEventAction`, `ExecutionController`)
- `platform-app/src/main/java/app/platform/data/BackgroundRecords.java`
