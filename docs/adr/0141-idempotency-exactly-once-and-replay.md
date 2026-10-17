# ADR-0141: Idempotency, exactly-once effects and replay

- **Status:** Accepted
- **Date:** 2026-10-17
- **Sprint:** S23
- **Related:** [ADR-0016](0016-transactional-outbox-and-idempotent-consumers.md), [ADR-0024](0024-mail-queue-and-notification-v0.md),
  [ADR-0137](0137-executions-asynchronous-after-commit-and-what-they-keep.md), [ADR-0142](0142-retry-policy-and-backoff.md),
  [ADR-0143](0143-dead-letters-parked-events-and-redrive.md)

## Context

The seam of Sprint 22 promises exactly-once for *database* effects: the action runs in the transaction that also marks its step
completed, so the effect and the step commit together or not at all. This sprint adds retries (a step is attempted again), dead letters
and replay (a person runs it again). Each must be shown not to do the same thing twice.

## Decision

1. **Attempts.** A *database* effect (update, create, queue a mail) commits with its step. A crash therefore never repeats a completed
   step; a resumed execution goes on after the steps that completed. A **retry** is a new attempt of the same step; the failed attempt
   rolled back, so nothing it did can have committed. Attempts are counted per step and only for attempts that *ended* (failed or
   succeeded); a worker that died mid-attempt leaves no mark, and the claim counter (ADR-0137) counts it instead. A crash is not a
   failed attempt and a failed attempt is not a crash.
2. **Replay is a new execution.** A person's replay of a dead letter makes a **new execution** linked to the old one (`replay_of`,
   `replay_generation` = the old one's + 1) that **resumes at the failed step** (`resume_step`; the steps before it completed and are
   not run again) against the record **as it is now**. The condition is **not** judged again: the person is deciding to run it. The
   new execution copies the chain of the old one. A record that is gone ends it as `record_gone`; a switched-off workflow, a removed
   one and an organization without the feature end it as for any execution (`workflow_inactive`, `workflow_removed`, `feature_off`),
   so a replay is never a way around them.
3. **Two replays at once make one.** The unique key `(tenant, replay_of, replay_generation)` admits one replay of an execution; the
   second click finds it and answers with the existing one ("already replayed"). A dead letter is replayed once; if the replay dead-letters
   too, that one can be replayed (generation 2).
4. **The key of an effect outside the database** is `executionId:position:generation`. It is **stable across the retries of one
   execution** (the same attempt-after-attempt, so a provider that remembers keys makes one effect) and **different across a replay**,
   because a replay is a person's deliberate decision to act again after a failure they have seen. No action of this release has such an
   effect; the first to have one (Milestone M7) inherits the rule and its test.
5. **What a mail does.** `SEND_EMAIL` queues the mail request in the step's transaction, so it is queued **once** however often the
   step is claimed or retried. Sending is the mail relay's job and has its own retries and dead letters (ADR-0024); nothing in the
   workflow module retries a send, and nobody adds a second retry on top.
6. **Cancel** is for an execution that has not started or is waiting for a retry (`QUEUED`); it is one atomic update that checks the
   status, so a worker that claims the same row at the same moment either wins (it runs) or loses (it is cancelled), never both.
7. **Feature off** skips a run *and* a replay (`feature_off`).

## Consequences

- `workflow_execution` gains `replay_of`, `replay_generation`, `resume_step`; the "one cause" check admits a replay as a cause.
- Bulk replay is a loop of single replays with a savepoint each: a result per item, no all-or-none.
