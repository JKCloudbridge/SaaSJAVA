# ADR-0150: Start approval as a workflow action

- **Status:** Accepted
- **Date:** 2026-10-18
- **Sprint:** S24
- **Related:** [ADR-0138](0138-workflow-actions-and-the-shape-of-their-settings.md), [ADR-0141](0141-idempotency-exactly-once-and-replay.md),
  [ADR-0142](0142-retry-policy-and-backoff.md), [ADR-0144](0144-what-sprint-23-leaves-out-and-why.md), [ADR-0149](0149-outcome-updates-and-the-events-that-carry-the-rest.md)

## Decision

1. **`START_APPROVAL` joins the catalogue** behind the Sprint 23 seam (`ActionExecutor`, `ActionRegistry`, `WorkflowActionTypes`). One
   setting, `process`, which names a process of the object (`refersTo` in the catalogue endpoint, so the browser holds no process name). The
   triggering record is the subject. It needs the plan feature `approvals`.
2. **Fire and forget.** The step starts the instance **in the step's transaction** (a database effect, exactly once with its step) and
   completes. The workflow does not wait for the person: a waiting step was kept out of Sprint 23 on purpose, and "the workflow resumes"
   (note 6, section 44) is delivered by the outcome events and the `APPROVAL_OUTCOME` trigger (ADR-0149).
3. **Stable reasons**, all **permanent** except as stated: `already_pending`, `entry_criteria_not_met`, `no_approver`, `feature_off`,
   `record_gone`, `process_inactive`, `process_missing`, `record_locked` (the record is locked by another request) and `record_in_bin`.
   **`already_pending` completes the step** with the reason recorded: the start is idempotent, so a retry after a crash and a replay never
   start a second instance (the unique index on pending instances is the backstop). `record_locked` cannot occur for the start itself
   (a locked record has a pending instance, which is `already_pending`).
4. **The submitter is "the platform"**: a named actor in the history, not a person. Approver rules that need a submitter (the exclusion of
   self-approval) treat it as nobody. The record's version is not asked (the platform judges the record as it is).
5. **The chain** of the execution is stored on the instance (ADR-0149 decision 7).
6. **Module edge `workflow -> approval`** through the narrow public interface `Approvals` in the `approval` root. `approval` never gains a
   `workflow` edge (an architecture test).
