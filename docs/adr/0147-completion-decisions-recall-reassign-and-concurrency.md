# ADR-0147: Completion, decisions, recall, reassign and concurrency

- **Status:** Accepted
- **Date:** 2026-10-18
- **Sprint:** S24
- **Related:** [ADR-0145](0145-the-approval-model-processes-versions-instances-and-states.md), [ADR-0146](0146-approver-resolution.md),
  [ADR-0089](0089-what-a-record-may-write-and-what-it-may-never-leak.md), [ADR-0148](0148-record-locking-while-an-approval-is-pending.md)

## Decision

1. **Completion, per step**: `ANY` (one assignee's approval completes the step, the other open assignments are `CLOSED`) or `ALL` (every
   assignment must be approved). No percentage, no quorum. **One rejection by any assignee ends the step as rejected and the instance as
   `REJECTED`**, whatever the completion rule; there is no "send back to the previous step" (ADR-0153).
2. **Actions** and who may do them:

   | Action | Who | Effect |
   |--------|-----|--------|
   | submit | a member who may edit the record (record-level security applies; a record they cannot read is not found) | creates the instance, resolves step 1, locks the record |
   | approve | an assignee of the active step, because of the assignment | the assignment is `APPROVED`; the step completes or waits; the next step becomes active or the instance is `APPROVED` |
   | reject | an assignee | the step and the instance are `REJECTED`; a comment is required |
   | recall | the submitter, or a member with `approvals.override-lock` | the instance is `RECALLED` |
   | reassign | an assignee hands **their own** open task to one other eligible person; or a member with `approvals.manage` assigns a person to a step that has nobody or adds one | recorded with both names; never to more than one person, never back to a person who already held the task (no loop) |
   | cancel | a member with `approvals.override-lock` | the instance is `CANCELLED`, the record is unlocked |

   **An approver acts because of the assignment, not because of an ability.** The assignment is checked in the transaction of the decision,
   and so is the standing of the person: a member who left, was deactivated or lost the licence in the meantime cannot decide.
3. **The comment** is the text of a person: bounded (2000 characters), optional on approve and required on reject, stored in the history
   row and shown only to people who may see the history. It is **never logged, never in an event, never in a mail, never in an audit
   record, never in a metric tag** (ADR-0089 spirit). The history shows no record value and no field the viewer may not read.
4. **Concurrency.** Every decision carries the **version of the instance** the caller read. A lost race is `409 CONCURRENT_MODIFICATION`
   and names nothing. Two approvers clicking at once on an `ANY` step make **one** outcome: the second finds the step no longer active (the
   assignment is `CLOSED`) and gets the same 409. A click twice by the same person is idempotent: the second finds its assignment already
   decided and is told so without a second effect. The lock order is in ADR-0148.
5. **Advancing** (resolve the next step's approvers, write the history, update the instance) happens **in the transaction of the
   decision**, together with the outcome updates and the unlock when the instance ends, and the `approval.*` event (via the outbox).
   There is no timer: nothing in this release waits on a clock.
6. **Idempotent starts.** Submitting a record that already has a pending instance by the same submitter returns that instance unchanged;
   by anybody else it is `already_pending` (409).
7. **Every action is audited** (`approval.*`): the process API name, the object, the record identifier, the kind, the version of the
   instance and the stable reason; never a comment, a value or the text of a criteria.
