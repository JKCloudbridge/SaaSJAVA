# ADR-0159: Reminders, escalation and the approval timer

- **Status:** Accepted
- **Date:** 2026-10-18
- **Sprint:** S25
- **Related:** [ADR-0134](0134-the-scheduler-of-timed-workflows-decision-d9.md), [ADR-0135](0135-the-workflow-dispatch-system-scope.md),
  [ADR-0145](0145-the-approval-model-processes-versions-instances-and-states.md), [ADR-0147](0147-completion-decisions-recall-reassign-and-concurrency.md),
  [ADR-0148](0148-record-locking-while-an-approval-is-pending.md), [ADR-0151](0151-delegation.md), [ADR-0153](0153-what-sprint-24-leaves-out-and-why.md),
  [ADR-0158](0158-module-edges-and-contracts-of-sprint-25.md)

## Context

Sprint 24 left out "escalation by time and reminders" because nothing in it waited on a clock. A step can now wait for ever on a person who
is away; the request is locked meanwhile (ADR-0148). The platform needs a timer, and the prompt says not to start a second kind of scheduler.

## Decision

1. **The definition gains two optional durations**, `remindAfterHours` and `escalateAfterHours` (1 to 8760), stored on the immutable
   version of the process (V057), so a change of either is a new version like any other change of the definition (ADR-0145) and a pending
   request keeps the timing it started under. Escalation needs the **fallback approver** that the process already names (a database check
   and the rules say so); there is no second name for "who to ask".
2. **What happens to an active step**, as a pure function (`ReminderSchedule`): reminders at `n x remindAfter` after the step became
   active, for n = 1 to 3 (a platform constant, at most three); **one escalation** at `escalateAfter`; then the request is marked
   **`overdue`**: after the third reminder (`4 x remindAfter`), or at the escalation for a process that only escalates. When the timer was
   down and several reminders fell due together, only the latest is sent and the earlier ones are passed over, so nobody is sent a burst.
3. **A reminder** goes to the people who still hold an **open task** of the step, delegates included (a delegation adds the delegate to
   the step; the delegator still holds theirs). Each gets an in-app notice and a mail (their preferences decide each, ADR-0156), a history
   line `REMINDER_SENT`, and the reminder counts toward nothing else. **Escalation** asks the fallback approver exactly as an administrator's
   `assign` does (the same state-machine transition, a task with `via = FALLBACK`): for a step that needs ALL, the new person is one more
   who must approve, which is stated in the screen. The fallback is not asked twice, and a person who already holds a task of the step is
   not given a second one. **Overdue** sets the request's attention to `overdue` (never over another attention such as `no_approver`) and
   writes `OVERDUE_MARKED`; it blocks nothing, and an administrator's `assign` or any decision clears it by the existing rules.
4. **Once, by rows.** `approval_reminder` has a unique key `(step, kind, sequence)`: the reminder with a number, the escalation and the
   overdue mark each happen once per step, whichever instance runs the timer and however often. The timer row records how many reminders
   were sent and when the step was escalated or marked overdue.
5. **The timer is the scheduler's pattern, not a second scheduler.** A due time kept in a row (`approval_step_timer.next_timer_at`),
   claimed with `FOR UPDATE SKIP LOCKED` and moved ahead by the lease (two minutes) by any instance, in the system scope `approval_timers`
   (ADR-0158). The row is a table of its own because a claim raises a version, and the version of the step is what a person's decision
   compares: a claim must never make a decision fail.
6. **The work on a step** runs in the step's own organization context and one transaction that first takes the same locks a decision takes
   (the record's row, then the request's, ADR-0148), so a reminder cannot interleave with a decision; a step decided while the timer
   waited is left alone and its timer stopped. Ending a request or leaving a step stops the timer (`ended_at`); the row stays as the record
   of what was done.
7. **Failure.** A step whose handling fails is reported (type name only) and tried again after the lease; a poison step is visible in the
   metric `platform.approval.timer.looks{result=failed}`.
8. **Notices and mail** are facts only (ADR-0154, ADR-0155): the process and step API names for the notice, the labels for the mail.
   `approvals.view-all` and `approvals.manage` see the overdue state in the lists, and `automation.view` in the failure view (ADR-0163).
9. **Settings** `platform.approval.timer.*` (`enabled`, `poll-interval` 30s, `batch-size` 50, `lease` 2m).
10. **The mail quiet period** (one minute per person about a task) stays and is documented; reminders are spaced by their own timer and are
    exempt.

## Consequences

- A request nobody answers is nudged three times, then flagged for an administrator, and optionally widened to the fallback approver.
- The timer costs one indexed claim per poll and nothing per step that has no timing.

## Alternatives considered

- **Reminders as a workflow schedule:** the schedule fires per record on a calendar; a reminder is relative to the moment a step became
  active and must stop the moment it is decided.
- **Timer columns on the step:** a claim would bump the step's version and could fail a person's decision.
- **Unlimited reminders:** a reminder that never stops is a nuisance; the mark `overdue` says what a person should do instead.

## References

- `platform-app/.../approval/internal/{ReminderSchedule,ApprovalTimer,ApprovalTimerStore,ApprovalEngine,ApprovalNotifier}.java`; V057
