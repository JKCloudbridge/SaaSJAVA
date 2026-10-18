# Runbook: automation, notices and trials

For the person who runs the platform. What to look at, what the numbers mean, what to do. Decisions: ADR-0133, ADR-0135, ADR-0159, ADR-0162
to ADR-0165.

## Where to look first

| What | Where |
|------|-------|
| One organization's automation | *Setup → Automation health* (needs `automation.view`) or `GET /api/v1/automation/health` |
| Every organization | *Console → Automation health* or `GET /api/v1/platform/automation/health` (platform administrators and support) |
| Why one execution failed | *Workflows → Executions* (the stable reason and the failing step; never a value) |
| Metrics | the management port `/actuator/prometheus` |

The view shows counts only. It never names an organization, a record or a person.

## The numbers

| Count | Meaning | When to act |
|-------|---------|-------------|
| Executions waiting | due executions not yet claimed | grows without end: the dispatcher is off or too slow; see "Queue is growing" |
| Longest wait (seconds) | how long the longest-waiting organization has waited (gauge `platform.workflow.queue.age{bucket}`, eight hash buckets, never a name) | above 10 minutes is flagged |
| Set aside for a person | dead letters | a person replays or cancels them; a rising count with one reason is a defect or a setup mistake |
| Events the platform gave up delivering | parked outbox events | put back from *Workflows → Parked events* once the cause is mended |
| Requests nobody can decide | approvals with the mark `nobody` | an administrator assigns a person |
| Steps that are overdue | approval steps whose reminders are used up | the people asked are reminded and the fallback is asked; a person acts |
| Reminders waiting for the timer | steps whose time has come but are not handled | stays above zero: the timer is off or stuck; see "Reminders do not come" |
| Mails waiting / could not be sent | the mail queue | dead mails: check the mail server settings; the queue retries by itself |

## Queue is growing

1. Is a dispatcher running? `PLATFORM_WORKFLOW_ENABLED` must be true on at least one instance. The metric `platform.workflow.queue.depth` should move.
2. One organization's long queue does not hold the others back: a claim takes at most `PLATFORM_WORKFLOW_PER_ORGANIZATION` (3) executions of one
   organization (ADR-0164). A long queue of one organization drains at the rate of the worker, three per look for that organization. Raise the number
   only after reading ADR-0164 and measuring with `benchmark/workflow` (`-Actions fair`).
3. A burst after an outage is normal: the lease of a dead worker ends after two minutes and another instance takes the work over; an execution is
   never lost and a completed step is never repeated (ADR-0141, ADR-0165).
4. An execution claimed more than three times is set aside as a dead letter: something kills the worker. Look at the failure type in its history.

## Reminders do not come

1. `PLATFORM_APPROVAL_TIMER_ENABLED` must be true on at least one instance (it is by default). Look at `platform.approval.timer.due`.
2. The process must have `remindAfterHours` or `escalateAfterHours`; a request started before they were set keeps the timing it started under.
3. A step that was decided, recalled or ended is never reminded: that is correct.
4. A person who switched the approval notices off gets no notice in the application, but the reminder is still in the history of the request.

## Before-save automation misbehaves

It runs inside every save of an object that has one. If saves are slow or refused with *The automations of this object took too long*, switch the
workflow off in *Setup → Workflows* (effective at once). As the operator's last resort set `PLATFORM_BEFORE_SAVE_ENABLED=false` and restart: no
before-save automation runs anywhere, and saves go through the rest of the pipeline unchanged. The metric `platform.data.before.save{result}` counts runs, skips
(`not_evaluable`) and refusals; an automation that cannot be evaluated is skipped and reported to the error tracking, never blocking the save.

## Trials

The job runs on every instance every ten minutes (`platform.licensing.trial.interval`) and is safe to run in several places: each stage is one marker
row. For an organization whose trial ended: nothing for the grace period (7 days by default), a warning to its administrators 7 and 1 day before the
suspension, then the suspension.

- To give a client more time, extend the trial in the console (a new end starts the stages again) or make the plan active (no trial to end).
- To reinstate a suspended organization, use *Console → Organizations*; a reinstated organization with an ended trial is **not** suspended again for
  the same trial end. Make the plan active first.
- The job never deletes anything. `PLATFORM_TRIAL_JOB_ENABLED=false` stops it.
- A mail goes to the administrators (the Organization administrator profile). An organization with no active administrator is suspended without a mail;
  the audit record `platform.organization.suspended` names the reason `trial_ended`.

## Notices

- Read notices are removed after 30 days, unread ones after 180, never before 7 (`platform.notification.notices.retention.*`).
- A notice is facts only; if a person says a notice shows the wrong words, the cause is the organization's template (*Setup → Notification text*),
  and *Back to the platform's text* mends it.
- Counts of notices are not in the failure view on purpose: they are not a failure.

## Switches and where they live

| Variable | Default | Effect |
|----------|---------|--------|
| `PLATFORM_WORKFLOW_ENABLED` | true | the dispatcher on this instance |
| `PLATFORM_WORKFLOW_PER_ORGANIZATION` | 3 | executions of one organization per claim |
| `PLATFORM_APPROVAL_TIMER_ENABLED` | true | the approval timer on this instance |
| `PLATFORM_TRIAL_JOB_ENABLED` | true | the trial job on this instance |
| `PLATFORM_BEFORE_SAVE_ENABLED` | true | before-save automation everywhere this instance saves |
| `PLATFORM_MAIL_RELAY_ENABLED` | true | the mail relay on this instance |
