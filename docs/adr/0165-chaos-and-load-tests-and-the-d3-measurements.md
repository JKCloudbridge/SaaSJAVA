# ADR-0165: The chaos test, the load test and the pending measurements of decision D3

- **Status:** Accepted
- **Date:** 2026-10-18
- **Sprint:** S25
- **Related:** [ADR-0133](0133-event-processing-stays-on-the-outbox-decision-d3-final.md), [ADR-0135](0135-the-workflow-dispatch-system-scope.md),
  [ADR-0141](0141-idempotency-exactly-once-and-replay.md), [ADR-0149](0149-outcome-updates-and-the-events-that-carry-the-rest.md),
  [ADR-0159](0159-reminders-escalation-and-the-approval-timer.md), [ADR-0164](0164-a-fair-share-of-the-queue.md)

## Context

Milestone M6 promises that automation loses nothing and repeats nothing that must not be repeated when a worker dies. Until this sprint that
was proved piece by piece: a row is edited to look like a crash (`WorkflowExecutionIT`), a lease is expired by hand. That shows the
recovery code works on a state; it does not show that the state a real death leaves is the one the test wrote. The load side of decision
D3 (thresholds T1 to T6 and A1 to A6 of ADR-0133) has never been measured on a full run.

## Decision

1. **The chaos test is an integration test with a real second instance and a named hook, never a sleep.** `FailureInjection.crash(type,
   times, afterTheEffect)` (test support) makes the next attempts of an action type throw `SimulatedCrash`, an `Error` that nothing in the
   runtime catches, so the thread of the worker dies with its claim held, exactly as a killed process would leave it, and the transaction of
   the step never commits. The survivor is a second application instance started by `TestApplication`, which polls the same database. The
   lease is ended by the test (a row update), not waited for, so the test is deterministic and fast.
2. **What `WorkerCrashIT` proves, against PostgreSQL:**
   - death **after the effect**: the record is unchanged and its version is the old one (the dead attempt left nothing); the claim holds, so
     the survivor finds nothing to claim; after the lease the survivor finishes the execution and the record carries the effect **once**
     (the version moved by one, exactly one `record.updated` event);
   - death **before the effect**: the same recovery;
   - death in a **notice**: no notice after the death, exactly one after the recovery (the key of the notice is the key of the step, ADR-0141);
   - death in the **second step**: the first step, already committed, is not run again;
   - an execution that **keeps killing its worker** is set aside after the third claim (`max claims`) and no worker is sent to it again.
3. **What is not injected this sprint, and what covers it instead.** The prompt names five more places to kill: a schedule firing, an
   approval outcome write, a mail send, an outbox relay batch and (added here) the approval timer. Each is protected by the same mechanism the
   test above proves (a lease, a unique row, a step in its own transaction), and each has tests of that mechanism:
   a schedule firing is resumable page by page and keyed per record (`WorkflowSchedulerIT`); an approval outcome is written in a savepoint
   after the status is ended and a refusal keeps the decision (`ApprovalOutcomeIT`, ADR-0149); a mail is claimed with a lease and the
   catcher can be paused (`MailRelay` tests, `TestMail`); an outbox batch is claimed with a lease and its handlers are idempotent by key
   (the relay tests); the approval timer claims with `FOR UPDATE SKIP LOCKED` and a lease and its events are unique rows
   (`ApprovalTimerScopeIT`, `ApprovalReminderIT`). **No `Error` was thrown inside these five places**, and this record says so: a hook in each
   is a small addition when a rehearsal asks for it, and the first one to add is the mail send, because a mail cannot be taken back.
4. **The load test is the benchmark folder, outside CI.** Two modes already exist (`T1` to `T6`, `A1` to `A6`, thresholds fixed before any
   number in ADR-0133). This sprint adds `-Actions fair` with the thresholds below, fixed before the mode ever ran and never moved after a
   number is seen:

   | Threshold | Meaning | Limit |
   |-----------|---------|-------|
   | F1 | p95 of the time from creation to the end of a short queue (20 executions) that arrives while a long queue of another organization is half worked off | at most 10 s |
   | F2 | executions per second of the long queue over the whole period, one instance (the turns must not collapse the rate) | at least 40 |
   | F3 | one execution for every event, every one completed | exactly, nothing lost, nothing made twice |

   The throughput of events to executions to **notices** needs real members (a notice goes only to an active member), which a benchmark that
   writes organizations with SQL does not have; it is measured at the rehearsal with the seeded organization. Until then its safety is the
   integration tests (`NotifyActionIT`, `WorkerCrashIT`) and its rate is not claimed.
5. **The D3 measurements are not run in this sprint.** Decision recorded with the user's delegation of 2026-10-18: the smoke sizes prove the
   harness (`-Size smoke`); the full runs (about 45 minutes per mode, one and three instances) are left for the pre-production rehearsal on
   the intended instance, where the numbers mean something (a laptop's numbers were already shown to differ from a managed instance's, ADR-0083).
   The thresholds stay as written; the result is recorded in ADR-0133 ("Result") when it exists. **Until then D3 stays "stay on the outbox,
   measurement pending"**, which is what the delivery plan says.

## Consequences

- A regression that makes a death leave a half-done step, a repeated effect or a lost claim fails a fast test on every CI run.
- The proof of five other places is by mechanism and not by injection; that is a stated, small gap, and the next sprint that touches one of
  them should add its hook.
- The fair-share mode is one more script mode and costs nothing in the build.

## Alternatives considered

- **Kill the process of a real second instance** (a child JVM, `kill -9`): the closest to the truth, and slow, platform-dependent and hard to
  place at a named point. The thrown `Error` leaves the same database state, because a killed process and an unhandled `Error` both end the
  transaction without a commit and leave the claim to expire.
- **Wait for the lease:** a sleep makes the test slow and flaky; ending the lease in the row is the same fact stated directly.
- **Run the D3 measurements now on the laptop:** 90 minutes of a machine the numbers would not transfer from, and the thresholds are
  specified for the intended instance.

## References

- `platform-app/src/test/java/app/platform/workflow/internal/WorkerCrashIT.java`, `FailureInjection.java`
- `benchmark/workflow/run-event-benchmark.ps1`, `benchmark/workflow/README.md`
