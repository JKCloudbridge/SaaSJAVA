# ADR-0187: Approval hooks, the write the lock lets through, and the step that is skipped when nobody can decide it

- **Status:** Accepted under the product owner's delegation of 2026-10-22 ("you decide the rest"). The one product question, the rule for a skipped step (section 6), is the recommendation put to them and was not separately confirmed: it is written so that it can be changed (section 9).
- **Date:** 2026-10-22
- **Sprint:** FA4A (Functional track; generic, no cluster word)
- **Related:** [ADR-0174](0174-template-versions-and-approval-together.md) (sections 5 and 6: what the engine needs, what the builder cannot do), [ADR-0176](0176-number-series-and-the-first-approvers-number.md) (section 2: the options compared), [ADR-0148](0148-record-locking-while-an-approval-is-pending.md) (the lock), [ADR-0145](0145-the-approval-model-processes-versions-instances-and-states.md) to [ADR-0153](0153-what-sprint-24-leaves-out-and-why.md) (the model; **ADR-0153 said "no step actions": this ADR reopens exactly that, in the narrow form below**), [ADR-0139](0139-background-writes-the-one-reviewed-path.md) and [ADR-0149](0149-outcome-updates-and-the-events-that-carry-the-rest.md) (background writes, outcome updates), [ADR-0159](0159-reminders-escalation-and-the-approval-timer.md) (timers), [ADR-0183](0183-a-field-only-the-platform-writes.md) (a field only the platform writes), [ADR-0012](0012-observability.md) (what a log may hold)
- **Evidence:** `ApprovalHooksIT` (11), `ApprovalSkippedStepIT` (8), `ApprovalHookCallsTest`, `ApprovalHookRegistryTest`, `RequestHookWriterTest`, `ApprovalMachineSkipTest` (with a property test), `RecordLockGuardTest`, `ApprovalProcessCodecTest`, `ApprovalArchitectureTest`, `WorkflowArchitectureTest`, and the builder tests of `platform-web`

## Context

The next sprint (FA4) puts a template through a process an administrator builds in the existing approval setup, and the first approver's own **Approve** gives the template its number, in the same transaction as the decision, while the record is locked. The engine could not do three things that needs:

1. **React inside its own transaction** when a request is submitted, when a person approves, and when it ends. It only moved its own rows; anything outside the record that must move with the decision would have had to wait for an event and could lag or be lost.
2. **Write to the record it locks.** A pending request locks its record against every write, the platform's own background writes included, and an approver holds no write access from a task.
3. **Skip a step nobody can decide.** A first step with nobody refuses the submit; a later one stalls the request ("attention") until an administrator acts. The business wants some steps optional.

This ADR is the smallest generic addition that makes all three possible. It contains no template, no number series entry, no publish and no cluster word; the cluster's own hook is FA4.

## Decision

### 1. The contract: `ApprovalHooks` in the shared kernel, found by object name

`app.platform.sharedkernel.approval.ApprovalHooks`, next to `RecordLocks` (the same inversion: `approval` calls it, a business module implements it, and neither `approval` nor `data` depends on that module; an architecture test says that nobody in `approval` or `data` implements it).

| Member | What it is |
|---|---|
| `object()` | the API name of the one object the hook serves |
| `writes()` | the field API names the hook may write (default none; at most 10) |
| `onSubmit(SubmitContext)` | a request is about to be created; may refuse |
| `beforeApprove(DecisionContext, ApprovalHookWriter)` | a person is about to approve; may refuse; is handed the writer |
| `onEnded(EndContext)` | the request ended; cannot refuse; no writer |

Every method has a do-nothing default. The contexts are records of **facts only**: request, object, record, process API name, process version number, the request version, the step's position and name, the person, the outcome. **No comment and no record value is ever handed over**: the hook reads the record itself through the record store, and it writes only through the writer (section 4). `ApprovalOutcome` has four members (`APPROVED`, `REJECTED`, `RECALLED`, `CANCELLED`): an administrator's cancellation is an end like the others, though it is not an outcome a process may attach field updates to.

`ApprovalHookRegistry` collects the beans once at start. **A second hook for the same object stops the application start and names the object**; so does an object or field name that is not shaped like one. For an object the platform itself defines, each field in `writes()` must exist and be **platform-written** ([ADR-0183](0183-a-field-only-the-platform-writes.md)); an organization's own object does not exist at start, so there the writer's refusal of any undeclared name (section 4) is the only line. An object with no hook costs one map lookup.

### 2. Where each call sits

| Call | Where | Notes |
|---|---|---|
| `onSubmit` | inside `begin`, **after** the first step with somebody was found and **before** the request row exists | both doors, a person's `submit` and the platform's `startByPlatform`. The engine makes the request's identifier first (`Uuids.v7()`) and hands it to the hook, so a hook can record "this version is under request X" at submit although nothing exists yet (ADR-0174 row 3 asks for exactly that). The refusal of the engine's own checks (entry criteria, nobody to ask) comes first, so a hook is never asked about a request that could not start |
| `beforeApprove` | inside `decide`, for **every approval of a task** (never a rejection), after the person's standing, their open task and the state machine's answer, **before anything is written** | with `ALL` completion it runs once per person; the hook decides for itself whether it has anything to do (FA4's: first step and a blank number) |
| `onEnded` | inside `end(...)`, **once**, after the status is saved and the timers stopped, **before** the outcome updates | the callers of `end` are four: a rejection, the last approval (or the last steps being skipped, section 6), a recall and a cancel. The timer never ends a request, and the tenant lifecycle does not touch requests |

The notice to approvers of a step is written when the step is activated, which is after `beforeApprove` and after `onSubmit` by construction, and it is in the same transaction anyway, so a hook that fails takes the notice and the mail queue row with it.

**A hook is asked to submit only for an object the organization has.** A shipped object whose plan feature is missing is left out of `Metadata`, so the cluster's hook never runs for an organization without the cluster (and no process can exist there). The other two calls are made **whenever the object has a hook**, even if the organization lost the feature meanwhile: a request that exists must be finished by the hook that saw it begin, or a version would stay "in review" for ever ([ADR-0152](0152-entitlement-abilities-visibility-and-telling-the-approver.md): a pending request finishes after a feature loss).

### 3. What a hook may break, and what the person sees

Every call is made in a **savepoint** (`ApprovalHookCalls`, the one place that calls hooks), so a refusal or failure undoes what the hook wrote in that call even when the caller is a workflow step that goes on with its own transaction.

- **A refusal** (`ApprovalHookRefusal`: a stable reason key and fixed words, both chosen by the hook's author at build time) is `409 CONFLICT` with those words for a person; for `startByPlatform` it is the stable `ApprovalStartException.Reason.HOOK_REFUSED` (a workflow reports a permanent failure). It is **audited by its key** (`approval.hook.refused`: the call, the object, the record, the identifier the request had or would have had, the process and the reason, never the words) **in a transaction of its own**, because the transaction that carried the refused change is about to roll back and would take the record with it. `onEnded` cannot refuse; a refusal there is treated as a failure.
- **Any other exception** becomes `ApprovalHookFailure`, which has **no message and no cause**: it rolls the whole decision back and is a plain `500` that tells the person nothing. The log line has the object, the call and the exception's type name; the exception's message is never kept (ADR-0012). It reaches the error tracking through the same path as any unexpected error, as a message-free failure.
- **Slow.** A call is **not interrupted** (it holds the record's row lock and an interrupted write could leave half a change) but it is **measured**: the timer `platform.approval.hook{call,result}` and, over `platform.approval.hooks.slow-call` (default 500 ms), the counter `platform.approval.hook.slow` and a log line by identifiers. A deadline is a later decision if the numbers ask for it.

### 4. The write the lock lets through

The lock *is* the pending request ([ADR-0148](0148-record-locking-while-an-approval-is-pending.md) decision 7), so **its owner may write while it is pending**. This is expressed as exactly that, not as an exemption for approvers, for a permission or for a kind of caller:

- `BackgroundWrites.Kind` gains `APPROVAL_HOOK` and `Source.approvalHook(process, request, chain)`.
- `RecordLocks` gains `ownedBy(object, record, request)`: true only when that request is the **pending** request of that record. `ApprovalLocks` answers it from its own table, so there is **no new module edge** (the guard already asks the contract).
- `RecordLockGuard.requireOpenForPlatform(object, record, source)` takes the row lock first, reads the lock, and lets the write through **only** for `APPROVAL_HOOK` with `ownedBy` true. A workflow, the outcome update of an approval, a hook of another request and a source with the right identifier but the wrong kind are all still refused with `record_locked`. Members are unchanged (the override ability, audited).
- **The hook never holds `BackgroundWrites`.** The engine hands it an `ApprovalHookWriter` (shared kernel) bound to **one request and its record**, limited to the fields in `writes()`, writing through the full write pipeline as the platform (no permission stage, a value never in a log, an audit record or an event: the audit names the field). It is closed when the call returns, so a hook that kept it cannot write later, when the lock may no longer be the request's. A name that was not declared is a bug of the hook (`IllegalStateException`, nothing written); a refusal of the pipeline is `ApprovalHookWriteException` with the store's stable key; the write itself is in a savepoint.
- **Only `beforeApprove` has a writer.** At `onSubmit` the request does not exist, so nothing owns the lock; at `onEnded` the request is no longer pending. A hook that needs to set fields at the end uses the outcome updates of the process.
- Architecture tests: inside `approval` only the engine, the call site and the writer name `BackgroundWrites` (and the public `Approvals` interface for its chain parameter); **only `approval` and `data` may make the source** `Source.approvalHook`; and a fixture class that does what a business module must not do is held against the rule, to prove the rule fails when it should.

### 5. The lock order, which needed no change

ADR-0148 decision 6 gives the order: access lock, member row, pool row, **record row, request row**, step and task rows. The prompt of this sprint feared that `decide` held only the request. It does not: every route that decides or ends a request (`approve`, `reject`, `recall`, `cancel`, `reassign`, `assign`, the timer) goes through `hold(id)`, which locks the **record first** and the request second. A hook that writes the record therefore takes a row it already holds (`lockRows` inside the same transaction). Two approvers deciding at once, or an approval racing a member's override edit, queue on the record row in that order. `ApprovalHooksIT` shows the counter, the number and the history in step; the concurrency tests are listed in the sprint document, with what was and was not run.

### 6. The step that is skipped when nobody can decide it (`skipWhenNobody`)

**The rule, in plain words** (the one product decision of the sprint): a step marked *"Skip this step when nobody can decide it"* is passed over when, **after the fallback approver was tried**, nobody can be asked. The fallback is still the first remedy; the skip is the last. A step that is not marked behaves exactly as before.

- **A skipped step** gets the status `SKIPPED` (the step table already allowed it, for the steps not reached when a request ends), a history line `STEP_SKIPPED` with the position and the stable reason `nobody` (no names), an audit record `approval.step.skipped` and a counter. It has **no task, no timer and no notice**, and an administrator cannot add to it (it is not active).
- **The same rule at every position.** At the start the engine looks for the first step that has somebody; the flagged steps before it are skipped, in order, in the same transaction as the submit. In the middle and at the end, `advance` repeats until a step has people or the steps are used up.
- **If the steps are used up after an approval, the request is approved**, through the same `end(APPROVED)` as a last approval: the same history line, outcome updates, notice, event and `onEnded`.
- **If nobody at all can be asked at submit** (every step is flagged and empty, or an unflagged step that must be decided has nobody) the submit is refused with `NO_APPROVER`, as today: there is nothing to approve. A process is **not** refused at save for having only flagged steps, because it may legitimately name people at submit time.
- **`ALL` completion, delegation, escalation.** A skipped step has no tasks, so `ALL` is moot; delegation only adds delegates beside people who are asked, so a step with nobody has none; escalation and reminders belong to an active step's timer, which a skipped step never has.
- **The person who submitted** sees the skipped step in the history ("Step skipped") and, as before, one notice when the request ends. Nobody is told about the step that was passed over.
- **The state machine** stays pure: `skip(model, position)` (only the next waiting step, with none active, after steps that are approved or skipped; skipping the last step approves the request provided something was approved) and the invariants were widened to allow the moment between two steps, with a property test over any pattern of skipped steps (a final state is reached, never two active steps, a skipped step has no task).

### 7. The model, the JSON and the database

- The flag is a property of the **step** in `ApprovalStepSpec` (the API record that is also the stored JSON), `ApprovalProcessDefinition.Step`, `ApprovalProcessRules` (kept as is), `ApprovalProcessCodec` and the try-it preview (`ApprovalResolutionView.ResolvedStep.skipped`). A process version **stored before the flag has no such key and reads as `false`**; a new version always writes it, so two spellings of the same definition compare equal. The change kinds and their undo carry whole steps and need no change. **The schema file `metadata-definitions.json` does not hold approval processes** (they are checked by the rules and the API records), so it is unchanged; the OpenAPI document and the generated client are.
- **Migration V065** (expand only): `STEP_SKIPPED` joins `approval_history_kind_known`. Nothing else needs the database: the step status `SKIPPED` and its time check exist since V055, and the flag lives in the version's JSON. No table is added, so nothing is registered in `TenantScopedTables`.
- The builder has one checkbox on the step card with a hint in plain words; the preview says which step would be skipped; the browser holds no list and decides nothing.

### 8. What is deliberately not done

| Not done | Why |
|---|---|
| A hook with a deadline that interrupts it | it holds a row lock; interrupting a write is worse than waiting; measured first |
| A writer at submit or at the end | nothing owns the lock at either moment (section 4) |
| A hook that refuses at the end | the request has ended; undoing it from there would un-end a decision people made |
| A hook for an object that is not named exactly | one object, one hook: a second stops the start |
| A task-scoped edit window for approvers | reopens ADR-0148 decision 3 and ADR-0146; the Approver policy with the override ability covers the product owner's rule (ADR-0174 section 4) |
| Refusing at save a process whose steps are all flagged | such a process can be right when people are named; the submit says no when nobody is |
| A step that is skipped for another reason (time, a condition on the record) | would be a conditional route, which ADR-0153 leaves out |

### 9. How to change a decision later

- **The words and the key of a refusal** are the hook's own; nothing in the engine needs to change.
- **Whether `onSubmit` also asks for an object the organization lost** (section 2) is one condition in `ApprovalHookCalls.submitted`.
- **The slow bound** is `platform.approval.hooks.slow-call`.
- **A different meaning of "skipped"** (for example "wait for an administrator instead") is the flag's use in `ApprovalEngine.begin` and `advance`; the machine's `skip` and the history line stay.
- **A second writable moment** (for example at the end) would need a new question on `RecordLocks` and a new source kind; it is a decision of its own.

## Consequences

- The engine gains one contract, one call site, one writer and one flag; the state machine, the lock order, the pending-request-is-the-lock rule and `CANCELLED` meaning only an override are unchanged.
- FA4 writes the real hook: on `beforeApprove` at step 1 with a blank number it takes the number from the series and writes it through the writer; on `onSubmit` and `onEnded` it moves the version row. It needs nothing else from the engine.
- A hook that depends on "the first step" should read the record (is the number blank?) rather than the position: if the first step is skipped, the first approval is of a later step.
- The hook runs while the record's row is locked: it must be short, and it must not call the approval endpoints.
