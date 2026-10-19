# ADR-0163: Observability of automation and the failure view

- **Status:** Accepted
- **Date:** 2026-10-18
- **Sprint:** S25
- **Related:** [ADR-0012](0012-observability.md), [ADR-0133](0133-event-processing-stays-on-the-outbox-decision-d3-final.md),
  [ADR-0137](0137-executions-asynchronous-after-commit-and-what-they-keep.md), [ADR-0143](0143-dead-letters-parked-events-and-redrive.md),
  [ADR-0159](0159-reminders-escalation-and-the-approval-timer.md), [ADR-0164](0164-a-fair-share-of-the-queue.md)

## Context

Sprints 22 and 23 added many metrics. Before external systems are put on top of automation (Milestone M7), an operator must be able to see
what waits, what retries, what failed and what is stuck, and an administrator must be able to see the same for their organization, without
a single value of a record in a tag, a log or a screen.

## Decision

1. **What exists** (Sprints 22 to 24): `platform.workflow.executions{result}`, `.events{result}`, `.event.delay`, `.event.handling`,
   `.queue.delay`, `.queue.depth`, `.dead_letters`, `.execution.duration`, `.schedule.firings`, `.loops`; spans `workflow.event`,
   `workflow.execution` (per attempt), `workflow.schedule`; `platform.approval.instances{result}`, `.decisions{kind}`, `.step.duration`,
   `.lock.overrides`; `platform.notification.mail{result}`; the outbox gauges and the traces across event, execution and step.
2. **What Sprint 25 adds:** `platform.workflow.queue.age{bucket}` (seconds the longest-waiting due execution of a bucket of
   organizations has waited; eight buckets by a hash of the organization, never its name, ADR-0164); `platform.approval.timer.due`,
   `platform.approval.steps.overdue`, `platform.approval.timer{result}` and `.timer.looks{result}`; `platform.notification.notices{kind,result}`
   and `.notices.removed`; `platform.data.before.save{result}` with its duration; `platform.trial.stages{result}`. **A tag is always a closed
   list** (a result, a kind, a bucket), never a value a person typed, an identifier of a person, a record or an organization; the leak tests
   scan tags.
3. **The failure view**, as counts and the place to look, through the shared-kernel contract `AutomationHealthSource` (ADR-0158):
   - **For an organization** (`GET /api/v1/automation/health`, ability `automation.view`, organization hosts only): executions waiting,
     running, waiting for a retry, waiting longer than 10 minutes, set aside as dead letters, events the platform gave up on, the longest
     wait; approvals waiting, with nobody to decide, overdue; mails waiting, the longest wait, mails that could not be sent. Each item says
     whether it needs attention and where to look (the lists behind it need their own abilities: `workflows.view`, `approvals.view-all`).
   - **For the console** (`GET /api/v1/platform/automation/health`, platform administrators and support, platform host only): the same
     across organizations, **without any name or value**: the queue depth, the dead letters, the longest wait, overdue steps, timers due,
     mails waiting and stuck. It reads what the instance last measured for its own gauges (the dispatcher, the timer) and one count of the
     platform-level mail table, so it opens no system scope and costs nothing the instance did not already pay.
   - The screens draw the sections as the API gives them and hold no list (a scan test).
4. **Alerts are a document, not code in this release.** `docs/runbooks/automation.md` lists each metric, what it means, a threshold to
   alert on and what to do. No alerting rules ship with the application.
5. **Never logged or tagged:** a value of a record, a template text, a placeholder value, a recipient, an address, an exception message of a
   library. A failure is logged by the name of its type and counted by a closed result.
6. **The error tracker** receives the unexpected only (an automation that overran, a condition that cannot be judged, a timer step that
   failed, a mail that went dead), with identifiers.

## Consequences

- An operator has one page of counts for the platform and an administrator one for the organization; both can say "nothing is wrong".
- Adding a part to the view is one bean that implements the contract.

## Alternatives considered

- **A system scope that counts everything on request:** every page load would cost a cross-organization scan; the instance already
  measures it for its gauges.
- **Dashboards and alert rules as code:** they depend on the operator's monitoring stack; a runbook is portable.

## References

- `platform-app/.../workflow/internal/{WorkflowHealth,AutomationHealthController}.java`; `.../approval/internal/ApprovalHealth.java`;
  `.../notification/internal/MailHealth.java`; `.../platformadmin/internal/PlatformAutomationHealthController.java`; `docs/runbooks/automation.md`
