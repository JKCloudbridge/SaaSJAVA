# ADR-0142: Retry policy and backoff

- **Status:** Accepted
- **Date:** 2026-10-17
- **Sprint:** S23
- **Related:** [ADR-0137](0137-executions-asynchronous-after-commit-and-what-they-keep.md), [ADR-0139](0139-background-writes-the-one-reviewed-path.md),
  [ADR-0141](0141-idempotency-exactly-once-and-replay.md), [ADR-0143](0143-dead-letters-parked-events-and-redrive.md)

## Context

Until Sprint 22 a step that threw ended the execution as `FAILED` and nothing was retried. A lock timeout is not a reason to give up;
a rejected value is not a reason to try five more times.

## Decision

1. **Classified from the type, never the message.** The failure of one attempt is classified from the *type* of the exception (and the
   SQL state code, which is a code and not a message), looking down the chain of causes:
   - **transient** (retried): a deadlock, a lock that could not be acquired, a serialization failure, the statement time limit, a lost
     or refused connection, a recoverable or transient data-access failure, a pool that could not give a connection, and the one
     transient reason of the data module (`concurrent_modification`);
   - **permanent** (not retried): everything else, in particular every reason of `BackgroundWriteException` except the above, and a
     stored action type this release does not carry out.
   An unknown exception is permanent: retrying what nobody understood only repeats it and hides it.
2. **Backoff** is exponential with a bounded upward jitter. The wait after the *n*-th failed attempt is
   `min(max-delay, initial-delay × multiplier^(n-1) + jitter)` with `jitter` a random amount between 0 and `jitter × the exponential
   term`. It is never below `initial-delay`, never above `max-delay`, and (because the multiplier is at least `1 + jitter`) never
   decreases before the cap. Settings `platform.workflow.retry.*`: `max-attempts` 5, `initial-delay` 30s, `max-delay` 1h,
   `multiplier` 2, `jitter` 0.2. A start with a multiplier below `1 + jitter` is refused.
3. **A definition may lower the attempts** of an action (`maxAttempts`, 1 to the platform cap) and never raise them. The number that
   counts is the lower of the two.
4. **The wait is in the row, not in a worker.** A failed transient attempt rolls the step back, records the attempt on the step
   (`attempts`, `last_failure_type`, `last_reason`, `next_attempt_at`, status `RETRYING`) in a transaction of its own, and puts the
   execution back to `QUEUED` with `run_at` = the next attempt time and reason `retry_scheduled`. The dispatcher claims it like any due
   work (`run_at <= now()`); no thread sleeps, no connection and no lock is held while it waits. The status list stays closed:
   `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED` (only Sprint 22 rows), `CANCELLED`, `SKIPPED`, `DEAD_LETTERED`.
5. **Attempts are counted per step; claims stay separate** ([ADR-0141](0141-idempotency-exactly-once-and-replay.md)).
6. **A step is attempted again in the same execution**, after the steps that completed (they are not repeated). The condition is
   judged again on every claim because the record may have changed (ADR-0137); that is a read and costs no attempt.
7. **A failure to decide** (reading the definition or the record threw) that is transient puts the execution back for another claim
   with the same backoff; claims are bounded by `platform.workflow.worker.max-attempts`, then the execution is dead-lettered
   (`attempts_exhausted`). A permanent one is dead-lettered (`decision_failed`).

## Consequences

- Metrics: `platform.workflow.attempts{action,result}`, `platform.workflow.retries`, `platform.workflow.retry.delay` (a histogram).
  A span per attempt carries the attempt number.
- The classifier and the backoff are pure and have no-database tests (property test: bounds and monotonicity).
