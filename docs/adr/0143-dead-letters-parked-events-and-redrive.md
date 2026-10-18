# ADR-0143: Dead letters of executions, parked events and redrive (amends ADR-0016)

- **Status:** Accepted
- **Date:** 2026-10-17
- **Sprint:** S23
- **Related:** [ADR-0016](0016-transactional-outbox-and-idempotent-consumers.md), [ADR-0134](0134-the-scheduler-of-timed-workflows-decision-d9.md),
  [ADR-0141](0141-idempotency-exactly-once-and-replay.md), [ADR-0142](0142-retry-policy-and-backoff.md)

## Context

When retries are spent, or a failure is permanent, an execution needs a person. Sprint 22 also recorded a known gap: an outbox event
that was parked as a dead letter (ADR-0016) because its handler kept failing is invisible. A parked `metadata.published` event leaves a
workflow without its schedule row until the next release or a redrive; a parked `record.*` event is a change that never started its
workflows.

## Decision

### Dead-lettered executions

1. **A new terminal status `DEAD_LETTERED`**, not a reuse of `FAILED`, so that "failed once and retried" and "needs a person" are different
   filters. An execution ends there when a step's attempts are spent, when a step fails permanently, when the stored action type is not
   carried out by this release (`unknown_action_type`) or when it was claimed too often (`attempts_exhausted`). It keeps the step, the
   attempt count, the stable reason and the **type name** of the exception, never a message or a value. `FAILED` remains only for rows
   made by Sprint 22.
2. **It is listed, filtered and replayed through the API.** Listing is `workflows.view`; **replay** (one and bulk) and the redrive below
   are `workflows.replay`; cancel and definitions are `workflows.manage`. A summary gives counts per workflow and reason.
3. **Retention.** A finished execution is kept 90 days (never fewer than 30, ADR-0137); a **dead letter is kept longer by default:**
   `platform.workflow.retention.dead-letter` = 180 days, never fewer than 30, so a person has time to look. An alert-ready gauge
   `platform.workflow.dead_letters` counts them.

### Parked events

4. **A shared-kernel contract `ParkedEvents`** (implemented by `outbox`, used by `workflow`, like `EventPublisher`; `workflow` still
   depends on no `outbox` type): list the dead events of given types in the **current organization** and put one back. It runs in the
   organization's own context under its row level security: no system scope, no cross-organization read.
5. **Redrive** sets a dead event back to pending (attempts zero, due now). It is safe because consumers are idempotent (ADR-0016): a
   consumer that already handled the event finds its marker and skips, one that did not, handles it. Redriving twice changes one row
   (the second finds nothing dead), and the schedule row of a workflow is unique, so the gap of Sprint 22 closes without a duplicate.
6. The listing shows the event type, when it happened, the attempts and the **type name** of the last failure; never the payload.
   Which types belong to the workflow consumers is the workflow module's knowledge (`record.created|updated|deleted`,
   `metadata.published`).
7. **ADR-0016 amendment:** a dead letter may be put back by a person with `workflows.replay` for the types the workflow module names.
   The relay, the claim, the lease and the dead-lettering itself are unchanged.

## Consequences

- Endpoints under `/api/v1/workflows`: `executions` (filters, `dead`), `executions/{id}/replay`, `executions/{id}/cancel`,
  `executions/replay` (bulk), `executions/dead-letters/summary`, `parked-events` and `parked-events/{id}/redrive`.
- Every replay, cancel and redrive is audited with names, identifiers and counts only.
