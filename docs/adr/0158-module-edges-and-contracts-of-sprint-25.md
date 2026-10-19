# ADR-0158: Module edges and contracts of Sprint 25

- **Status:** Accepted
- **Date:** 2026-10-18
- **Sprint:** S25
- **Related:** [ADR-0001](0001-modular-monolith.md), [ADR-0015](0015-row-level-security-implementation.md),
  [ADR-0135](0135-the-workflow-dispatch-system-scope.md), [ADR-0154](0154-the-notification-model-in-app-notices.md),
  [ADR-0155](0155-the-text-of-notices-and-mails.md), [ADR-0159](0159-reminders-escalation-and-the-approval-timer.md)

## Decision

Every dependency Sprint 25 adds is written here, in `docs/modules.md` and in the `package-info.java` of the module, and held by an
architecture test.

1. **Two new edges: `notification -> metadata` and `approval -> observability`.** The second lets the approval timer report a step
   it could not handle to the error tracking, as `data` and `workflow` already do; `observability` depends on the shared kernel only.
   The first: The notification module reads the text an administrator wrote and the labels of
   objects, approval processes and workflows when it builds the words of a notice or a mail (ADR-0155). `metadata` knows nothing of
   `notification`, so there is no cycle. Nothing else in `notification` reads another module's tables.
2. **Contracts in the shared kernel, so that no business module depends on `notification`:**
   - `Notices` (with `NoticeKind`, `NoticeRequest`): create an in-app notice for a member of the current organization (ADR-0154). Used by
     `workflow` (the action `NOTIFY`), `approval` (tasks, reminders, decisions) and `platformadmin` (the end of a trial). Implemented by
     `notification`.
   - `NoticeText`: the rules of a text and the insertion of labels into it (pure logic), used by `metadata` at save and by `notification` at
     read, so a text that passes can always be rendered.
   - `AutomationHealthSource` (with `HealthSection`, `HealthItem`): a module's counts for the failure view (ADR-0163). Implemented by
     `workflow`, `approval` and `notification`; collected by the controller of the organization (in `workflow`) and by the controller of the
     console (in `platformadmin`).
   - `MailTemplate` gains `APPROVAL_REMINDER` and `TRIAL_NOTICE`.
3. **Small additions to existing contracts:** `security.MemberAccess.administrators()` (the users who hold the Organization administrator
   profile, for the trial notice), `licensing.Subscriptions.overTrials|trialStagesDone|markTrialStage` (platform-level reads and one marker
   write), `metadata.NotificationTemplates` (the published text), the ability `automation.view`. No new edge: `platformadmin` already
   depends on `security` and `licensing`.
4. **A second system scope, `approval_timers`** (`SystemScope.APPROVAL_TIMERS`, amends ADR-0015 and ADR-0135 in the same way): it admits
   select and update on `approval_step_timer` and on no other table, so the timer can find the steps whose time has come across
   organizations in one indexed statement. `approval` joins `tenant`, `outbox`, `identity` and `workflow` as a module that may name
   `SystemScope`, and inside `approval` only `ApprovalTimer` and `ApprovalTimerStore` may (an architecture test, as for the dispatcher). The
   timer claims and counts; the work on a claimed step always runs in the step's own organization context. Nothing else was given a scope:
   the retention of notices and the trial job walk the organizations or read platform-level tables, as the recycle bin clean-up does.
5. **Architecture tests added:** nothing but `notification` names the notice tables; nothing in `data` names `notification`; every creator of
   a notice goes through `Notices`; only the timer enters `approval_timers`; `package-info` allowed lists match `modules.md`.

## Consequences

- The graph stays acyclic and small: one new edge, and the contracts in the shared kernel do the rest.
- Adding a kind of notice is one enum entry (its category, its floor, its facts); adding a source of health is one bean.

## Alternatives considered

- **`workflow -> notification` and `approval -> notification`:** two more edges and a dependency of the business modules on the one that
  sends; the mail queue already shows the contract pattern is enough.
- **Templates in `notification`:** no `notification -> metadata` edge, but no change sets and no rollback (ADR-0155).
- **Counting across organizations with a broad scope:** each source reports what its own instance measured; no scope is opened for a screen.

## References

- `docs/modules.md`; `platform-app/src/test/java/app/platform/architecture/`
