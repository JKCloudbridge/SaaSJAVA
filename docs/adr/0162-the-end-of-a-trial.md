# ADR-0162: The end of a trial

- **Status:** Accepted
- **Date:** 2026-10-18
- **Sprint:** S25
- **Amends:** [ADR-0033](0033-subscriptions-trials-and-the-organization-limit.md) (decision 3: "it is recorded and shown, nothing switches off")
- **Related:** [ADR-0035](0035-controlled-support-access.md), [ADR-0038](0038-organization-lifecycle-by-platform-administrators.md),
  [ADR-0156](0156-preferences-and-the-floor.md), [ADR-0158](0158-module-edges-and-contracts-of-sprint-25.md)

## Context

ADR-0033 left the end of a trial to a platform administrator and promised the scheduler of Sprint 25. A trial that is over today is shown
as expired and nothing happens, so an unpaid organization runs for ever, and nobody tells its administrators that the end is near.

## Decision

1. **What an ended trial does: a grace period, warnings, then suspension through the lifecycle that exists.** The organization keeps
   working for the **grace period** after `trial_ends_at` (`platform.licensing.trial.grace`, default **7 days**). Its administrators are
   warned **7 days and 1 day before the suspension** (the first and the final notice) and told **when it is suspended**. The suspension is
   `Tenants.suspend` plus the sign-out of the organization, exactly what a platform administrator does by hand (ADR-0038): the host answers
   "not available", sessions end, data is kept, a platform administrator reinstates it when a plan is active. **No second state is invented:
   the subscription stays what it is** (a trial whose end passed), and reinstating does not re-suspend it (below).
2. **What it never does: delete.** No record, no member, no file and no definition is removed, and an organization that is suspended by the
   job is indistinguishable in its data from one suspended by a person. Read-only mode was considered and rejected: it is a third state of an
   organization that every write path would have to honour.
3. **A pure policy** (`TrialEndPolicy`): the suspension falls at `trial_ends_at + grace`; the notices at that moment minus the notice days,
   **never earlier than the end of the trial**; the stages due at a moment are those whose time has come and that have not happened; **only the
   latest due stage is acted on** (a job that was down does not send three mails at once), the earlier ones are only noted.
4. **Once per stage, per end of the trial.** The platform-level table `subscription_notice` (V057) is unique by subscription, stage and the
   end of the trial it belongs to. Whichever instance inserts the row does the stage; the others find it done. **A trial that is extended has
   a new end, and its stages start again**; a subscription made active (or suspended, or cancelled) is no longer a trial and the job leaves it
   alone. A manual reinstatement during the same trial is not undone: the suspension of that trial has happened once.
5. **Idempotent and safe.** A stage and what it causes (the mails to the administrators, the notices, the suspension, the sign-out, the
   audit record) are one transaction in the organization's own tenant context; if any step fails the marker is not kept and the next look
   does it again. Inside it the job re-reads the subscription and the organization, so a plan made active or an organization suspended a
   moment ago is respected. An organization that is still being set up (`PROVISIONING`) is skipped; one already suspended or closed is
   noted and not told.
6. **The administrators** are the users of the active members who hold the Organization administrator profile (`MemberAccess.administrators`).
   They get the platform mail `TRIAL_NOTICE` (facts only: the organization and the stage) and, before the suspension, an in-app notice. The
   notice cannot be switched off (the `ACCOUNT` floor, ADR-0156).
7. **Runs as the platform.** Its audit records name the system as the actor and say `trial_ended`; the console shows the organization as
   suspended with the reason of any suspension. **Settings** `platform.licensing.trial.*`: `enabled`, `grace` (7d), `first-notice-days` (7),
   `final-notice-days` (1), `interval` (10m), `batch-size` (50); validated at start.
8. **Payment is still out of scope.** Nothing here takes money or knows a processor; "a plan is active" is a platform administrator's act.

## Consequences

- An unpaid organization stops after a known time, and its administrators were told twice.
- The job is cheap: one indexed read of trials that are over, and nothing for the others.

## Alternatives considered

- **Suspend at the end of the trial with no grace:** surprises people whose work stops at midnight. A short grace and two warnings cost little.
- **Read-only:** a new state in every write path.
- **Mark the subscription `SUSPENDED`:** a second state to keep equal to the organization's; the markers are enough.

## References

- `platform-app/.../platformadmin/internal/{TrialEndPolicy,TrialEndJob,TrialEndProperties}.java`; `licensing/Subscriptions.java`; V057
