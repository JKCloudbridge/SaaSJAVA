# ADR-0164: A fair share of the queue between organizations

- **Status:** Accepted
- **Date:** 2026-10-18
- **Sprint:** S25
- **Related:** [ADR-0133](0133-event-processing-stays-on-the-outbox-decision-d3-final.md), [ADR-0135](0135-the-workflow-dispatch-system-scope.md),
  [ADR-0140](0140-loop-and-recursion-protection.md), [ADR-0144](0144-what-sprint-23-leaves-out-and-why.md)

## Context

The rate guard of ADR-0140 bounds one object of one organization (past a number of executions per minute the rest are delayed). The
dispatcher claims the due executions of every organization **oldest first, across all of them**: an organization with a long queue keeps
the oldest rows for a long time, and the others wait behind it. ADR-0144 named this as left for Sprint 25.

## Decision

1. **Round robin with a cap.** One claim takes at most `platform.workflow.worker.per-organization` (default **3**) executions of one
   organization, and takes them **by turns**: every organization that has work gets its first execution before any gets its second. The
   claim is one statement: a skip scan finds the organizations that have something due (one probe per organization, on the index
   `(tenant, run_at, id)` of V057), each contributes its own oldest rows up to the cap numbered by turn, the first `batch-size` rows in the
   order of turn then age are chosen, locked with `skip locked` and claimed. Two instances that choose the same rows split them; the loser
   claims fewer this time. The rule is a property of the ordering `(turn, age)`: **no organization with work waits more than a round
   behind another, and none gets more than the cap per claim**.
2. **Why not weighted by plan.** A weight needs a rule about whose work matters more, and nothing in the requirements names one. The cap is
   a setting; a weighted cap per plan is a later change that touches only the claim.
3. **A metric of queue age per bucket, never per organization.** The dispatcher samples, every 30 seconds, how long the longest-waiting due
   execution of each of eight buckets (a hash of the organization identifier modulo eight) has waited:
   `platform.workflow.queue.age{bucket}`. The tag has eight values and names nobody. The console's failure view reports the largest
   (ADR-0163).
4. **Starvation is bounded, not impossible.** An organization within its cap is served as often as any other; one with a long queue is
   served at the cap per claim and no more. Throughput for a single organization is `cap x claims per second`; a deployment that needs
   more for one tenant raises the cap, which the setting allows (it must not exceed the batch size).
5. **Unchanged:** the lease, the claim token (the version after the claim), the retry and replay rules, the rate guard.

## Consequences

- One organization's backlog no longer delays another's first execution by more than a round.
- The claim costs a few more index probes than the old one when many organizations have work, and the same when one has.

## Alternatives considered

- **Per-organization queues and a worker pool each:** more moving parts; the shared table with a fair claim does the same.
- **Priority by plan or age:** age alone is the starvation; priority needs a policy nobody has stated.

## References

- `platform-app/.../workflow/internal/{DispatchStore,Dispatcher,WorkflowProperties}.java`; V057 (`workflow_execution_due_by_tenant`)
