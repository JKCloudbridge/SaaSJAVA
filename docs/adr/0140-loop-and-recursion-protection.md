# ADR-0140: Loop and recursion protection

- **Status:** Accepted
- **Date:** 2026-10-17
- **Sprint:** S23
- **Related:** [ADR-0133](0133-event-processing-stays-on-the-outbox-decision-d3-final.md), [ADR-0137](0137-executions-asynchronous-after-commit-and-what-they-keep.md),
  [ADR-0139](0139-background-writes-the-one-reviewed-path.md)

## Context

An update made by a workflow produces `record.updated`, which can start workflows, including the one that made it. Without a guard a
single misconfigured workflow fills the queue forever.

## Decision

Four layers; all are tested.

1. **A chain.** Every execution carries the *chain* that led to it: `chain_root` (the identifier of the first cause: the event of the
   write a person made, or the execution that a schedule firing started), `chain_depth` (how many workflow executions are before it;
   a person's write gives 0) and `chain_workflows` (the identifiers of the workflows already in the chain, not counting itself).
   A workflow is identified by the workflow, not by one of its versions: a workflow published again halfway through a chain is the
   same workflow, and the depth bound still catches anything else.
2. **The chain travels in the event.** A write made by an action publishes `record.created|updated` with an optional `chain` object
   in the payload (`root`, `depth` = the executing execution's depth + 1, `workflows` = its chain plus itself). Identifiers and one
   number only, never a value. Old events and events of people's writes have no `chain` and are depth 0 with an empty set (a
   backward-compatible, optional field).
3. **The handler refuses to extend a bad chain.** For a workflow whose trigger matches, if the workflow is already in the chain the
   handler makes an execution that is `SKIPPED` with reason `loop_detected`; if the depth is at or above
   `platform.workflow.limits.max-chain-depth` (default 5) it makes one that is `SKIPPED` with `depth_exceeded`. The skipped rows are
   made on purpose: they are how an administrator sees that a loop was stopped, and the unique key still makes one per event and
   version. With a limit of 5, a chain of five different workflows runs and the sixth is refused.
4. **A last line: a rate.** The handler counts the executions created for the same object in the last minute. Past
   `platform.workflow.limits.max-executions-per-minute` (default 600) it does not drop the execution: it delays it by whole minutes
   in proportion to the excess (reason `rate_limited` while it waits), so a flood drains at the cap and nothing is lost. The count
   is asked only after a workflow matched, so an organization with no workflow for an object still costs the one snapshot lookup
   (ADR-0133, threshold T6).

**Allowed on purpose:** workflow A updates a record and workflow B, on a *different* workflow, starts on that update and sends a mail
(a chain of two). **Refused:** A starts A (a workflow that updates the field it triggers on, directly or through B). A schedule
firing starts a chain at depth 0 with itself as the root.

## Consequences

- The execution rows gain three columns (V054); `ExecutionRunner` passes the chain to the action context; `RecordChanges` adds it to
  the payload only for a write by an action.
- Metric `platform.workflow.loops{result=loop_detected|depth_exceeded|rate_limited}`.
