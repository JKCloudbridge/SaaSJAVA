# ADR-0157: Asynchronous delivery, and once

- **Status:** Accepted
- **Date:** 2026-10-18
- **Sprint:** S25
- **Related:** [ADR-0016](0016-transactional-outbox-and-idempotent-consumers.md), [ADR-0024](0024-mail-queue-and-notification-v0.md),
  [ADR-0141](0141-idempotency-exactly-once-and-replay.md), [ADR-0154](0154-the-notification-model-in-app-notices.md)

## Context

Architecture note 6 (sections 13 and 14) says what happens inside the transaction of a record write and what only after commit: validation,
authorization and critical constraints inside; workflow processing and notifications after. Nothing in a person's request may wait for a
notice or a mail, and neither may be lost or doubled when a worker, a relay or an instance dies.

## Decision

1. **Nothing in a person's request waits for delivery.** A notice is a row written in a transaction; a mail is a row of the mail queue.
   Neither is sent from a request. The sender of a mail is the relay; the "sender" of a notice is the member's next read.
2. **A notice is written in the transaction of whatever caused it**, so a rolled-back change leaves no notice and a committed one always
   has its notice: a step of a workflow (`NOTIFY`), a task of an approval, the end of a request, the timer. Handlers of events are the
   second source: a handler runs once per event in the outbox's transaction that also holds its idempotency marker (ADR-0016).
3. **Once, by key.** The key of a notice is made of identifiers that are stable across every repeat of its cause: the idempotency key of the
   workflow step (`execution:position:generation`, ADR-0141), the step and the person (a task), the step, the number and the person (a
   reminder), the request (a decision), the subscription, stage and end of the trial (a trial notice). The unique index turns every repeat
   into `DUPLICATE`. A **retry** of a step that failed after the notice was written rolled back with it and makes it again; a **replay** is a
   new execution and an intended new notice.
4. **Mail stays on the queue of Sprint 4**: facts only, retries with backoff, dead letters, a quiet period of one minute for a person
   mailed a moment ago about a task (not for reminders, which are spaced by their own timer). The mail request is written in the same
   transaction as the notice, so the two channels stand or fall together; a person's choice decides each channel separately (ADR-0156).
5. **Failure reasons of `NOTIFY`** (stable keys, ADR-0161): permanent `object_unavailable`, `record_gone`, `recipient_field_gone`,
   `recipient_missing`, `recipient_not_member`. A preference that is off is not a failure: the step completes.
6. **Metrics, not logs.** `platform.notification.notices{kind,result}` (created, duplicate, suppressed), `platform.notification.mail{result}`
   (sent, suppressed, retried, dead), the existing mail relay audit. Neither carries a recipient, a record or a key.

## Consequences

- A crash at any point leaves either both the change and its notice or neither; a redelivery cannot double a notice.
- Delivery of a mail may be at least once (ADR-0024); the text of the mail is the same either time.

## Alternatives considered

- **Create notices in a handler of the outbox event of every record write:** correct but a second hop for every notice; the action and the
  approval engine already know in their own transaction that a person must be told.
- **Server push (a socket) for the bell:** a second channel to secure and keep alive for little gain; the bell asks on focus (ADR-0166).

## References

- `platform-app/.../workflow/internal/NotifyAction.java`; `.../approval/internal/ApprovalNotifier.java`; `.../notification/internal/DefaultNotices.java`
