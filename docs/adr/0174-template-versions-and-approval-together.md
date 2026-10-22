# ADR-0174: Template versions and approval together

- **Status:** Accepted (2026-10-21, with the FA1 go-ahead); **built in FA4** ([ADR-0188](0188-the-template-lifecycle-the-cluster-module-publishing-and-the-master-list.md), [ADR-0189](0189-who-sees-a-template-the-fixed-private-default-abilities-and-field-rights-of-shipped-policies-the-approvers-edit-and-the-open-task-read.md)): sections 2 (rows 3 to 9), 3 and 4
- **Date:** 2026-10-21
- **Sprint:** FA0 (Functional track, spike; no product code)
- **Related:** [ADR-0145](0145-the-approval-model-processes-versions-instances-and-states.md), [ADR-0148](0148-record-locking-while-an-approval-is-pending.md), [ADR-0149](0149-outcome-updates-and-the-events-that-carry-the-rest.md), [ADR-0153](0153-what-sprint-24-leaves-out-and-why.md), [ADR-0139](0139-background-writes-the-one-reviewed-path.md), [ADR-0173](0173-the-canonical-grid-json-and-its-limits.md), [ADR-0176](0176-number-series-and-the-first-approvers-number.md), [ADR-0178](0178-shares-to-roles.md)
- **How it was settled:** by reading the code named below (nothing of the product was run: FA0 builds no product code). The one claim that needs a running system, that a pending approval does not stop a read of the active version, is **by construction** (section 3) and becomes an integration test in FA4.

## Context

The analysis says: a version is immutable once submitted; an approval acts on a **version**; the previous published version stays usable during a second review. Sprint 24 does something different: the approval request is pinned to a **record** (and its record version at submit),
and a pending request **locks the record** against every write path (ADR-0148). The product owner has since added: **versions are numbered automatically, the one in use carries an "active" tag**, and **while a template is in approval only approvers can edit it, nobody else**.
This ADR works out what a second review of a published template really does, and what the approval engine must offer for it.

## What the code does today (read, not run)

- **The lock** is `RecordLockGuard`. It is called from exactly three seams: the record permission stage of `DefaultRecords` (update, delete, restore, purge: `lockGuard.requireOpen`, line 229), `DefaultBackgroundWrites` (`requireOpenForPlatform`, line 87, which refuses with `RECORD_LOCKED`), and `RecordAuthorization.forEdit` (owner, files; **shares are allowed**).
  **No read path calls it.** A source-scanning test (`RecordLockGuardCoverageTest`) keeps new write paths from skipping it. The lock is "a request of the record is `PENDING`" (`ApprovalLocks implements RecordLocks`): no separate row.
- **Who passes the lock:** a member who holds the ability `approvals.override-lock` (audited: `RecordLocks.overridden` writes `LOCK_OVERRIDDEN` to the request's history and `approval.lock.overridden` to the audit). The submitter does not; the platform does not.
- **An approver has no access from a task** (ADR-0146, ADR-0148): `decide` checks the open task and the person's standing (active member, licence), nothing about the record. A task does not let a person edit.
- **The request** is pinned to the record and to the **record's version at submit** (`insertInstance(... record.version() ...)`) and to the **process version**. It knows nothing about a grid or a "template version".
- **Outcomes** (`Outcomes(approved, rejected, recalled)`) are lists of `Assignment(field, literal)` written through `BackgroundWrites` after the request has ended. A literal is a constant written as a field default is written: **it cannot say "the version under review"**.
- **The process model** (`ApprovalProcessDefinition`): entry criteria, `allowSelfApproval`, steps (name, label, `ANY` or `ALL`, approver rules of seven kinds including `RECORD_USER`), outcomes, a fallback, reminder and escalation hours. Nothing runs at submit; nothing runs when a step is approved; a first step that resolves to nobody **refuses the submit**, a later one raises "attention" and waits for an administrator.
- **Public contracts a cluster could use:** `Approvals` (only `startByPlatform`); the shared-kernel `RecordLocks`; the events `approval.*` through the outbox. Everything else in `approval` is internal.

## Decision

### 1. The model

**The header record** (`InspectionTemplate`, [ADR-0180](0180-names-the-module-the-objects-and-the-words.md)) holds the stable facts only: name, number, category, sub-category, dimension and rows mode, reviewer, approver, the owner, and `retired`. **It holds no version pointer.**

**The version row** (`sheet_version`, [ADR-0173](0173-the-canonical-grid-json-and-its-limits.md)): `version_no` (**automatic**: the highest of the template plus one, taken under a transaction-level advisory lock on the template, as the access lock is, and unique per template), `status`, `grid`, `grid_hash`, `submitted_at`, `decided_at`, the identifier of the approval request.

| Status | Meaning | Shown as |
|---|---|---|
| `DRAFT` | being written; the only status whose grid can change (at most one per template) | Draft |
| `IN_REVIEW` | submitted, request pending (at most one per template) | In review |
| `APPROVED` | the last step approved; not yet in use | Approved |
| `ACTIVE` | **the version in use: the "active" tag** (at most one per template: a partial unique index `where status = 'ACTIVE'`) | **Active** |
| `SUPERSEDED` | was active, replaced by a newer active version | Replaced |
| `REJECTED` | a step rejected it (comments and history kept) | Rejected |
| `WITHDRAWN` | the submitter recalled it, or an administrator cancelled the request | Withdrawn |
| `RETIRED` | was active when the template was retired | Retired |

**"Active" is derived, never copied.** The active version of a template is its `ACTIVE` row; the header stores no `publishedVersion` or `latestVersion`. That is deliberate: the header is **locked** during a review, and an outcome literal cannot name a version, so a pointer in the header
could not be moved by the approval and would force a write through the lock. With no pointer, "approve", "publish" and "retire" never need to write a locked record, except for the number ([ADR-0176](0176-number-series-and-the-first-approvers-number.md)).
The master list and the template page get the lifecycle ("Active v3, v4 in review") from the cluster's own endpoint, which pages the header through `Records.query` (so record security applies) and adds the version summary of those ids with one query that never selects `grid`.

### 2. The transitions: who may, what each writes

| # | From | To | Act and who | What is written (one transaction each) |
|---|---|---|---|---|
| 1 | none | `DRAFT` v(n+1) | **New version** (a copy of the active or latest version, or a blank grid for the first): holder of the right to change templates | a version row; nothing else |
| 2 | `DRAFT` | `DRAFT` | **Save**, with the row version read | grid and hash, version +1; refused if the header is locked, unless the caller holds the override (section 4) |
| 3 | `DRAFT` | `IN_REVIEW` | the existing **Submit for approval** button | hook `onSubmit` ([ADR-0176](0176-number-series-and-the-first-approvers-number.md)): checks the draft is valid and non-empty, sets `IN_REVIEW`, records the request id; **a database trigger makes the grid immutable from here** |
| 4 | `IN_REVIEW` | `APPROVED` | the last approver of the last step | hook `onEnded(APPROVED)` in the engine's transaction: status, `decided_at` |
| 5 | `IN_REVIEW` | `REJECTED` | any approver rejects (comment required) | hook `onEnded(REJECTED)` |
| 6 | `IN_REVIEW` | `WITHDRAWN` | the submitter recalls, or the override holder cancels | hook `onEnded(RECALLED / CANCELLED)`; the author makes a new version from it |
| 7 | `APPROVED` | `ACTIVE` | **Publish** to an audience: holder of `templates.publish` | the previous `ACTIVE` becomes `SUPERSEDED`; shares to the audience on the header ([ADR-0178](0178-shares-to-roles.md)); audit by counts. **Allowed while another version is in review** (shares are allowed on a locked record, ADR-0148 decision 2) |
| 8 | `ACTIVE` | `APPROVED` | **Unpublish** | the shares of the audience are removed; the template cannot be filled until a version is active again |
| 9 | `ACTIVE` | `RETIRED` | **Retire the template**: holder of `templates.retire`, no request pending | `retired = true` on the header (a normal member write, so the lock applies); the active version becomes `RETIRED`; **nothing is deleted** |

A version that is not `DRAFT` is **never changed again**, by anyone, except the narrow review edit of section 4. A rejected or withdrawn version is not reopened: a new version is made from it, so its comments and history stay as they were
(acceptance criterion 7). Version numbers are not reused and gaps are possible (a rejected version keeps its number).

### 3. A second review of an active template, exactly

Take template T with version 3 `ACTIVE` and version 4 submitted:

- **Locked:** the **header** (every member write, owner, files; background writes). Not locked: the version table, the shares, reads of anything.
- **What a filler still reads:** version 3. The filler's read is `GET` of the active version: authorized by **read** on the header (a record read, which no lock touches) and **by the status of the version row** (only `ACTIVE`, `SUPERSEDED` or `RETIRED` rows are readable on the strength of read access;
  draft, in-review, rejected and withdrawn rows are readable only by someone who may change templates, or an approver of that request). A role share therefore **cannot leak a draft**: the share is on the header, and the header holds no grid.
  Starting a fill-in (later) pins version 3 and is not stopped by the lock.
- **What an approval of version 4 does:** step by step the tasks; on the last approval, in the same transaction, version 4 becomes `APPROVED`. **Version 3 stays `ACTIVE`** until someone publishes version 4; that act makes 4 `ACTIVE` and 3 `SUPERSEDED` at once.
- **On reject or recall:** version 4 becomes `REJECTED` or `WITHDRAWN`; version 3 never stopped being active; the header unlocks (the request ended).
- **A new version while one is in review:** refused: one `DRAFT` and one `IN_REVIEW` at most per template (partial unique indexes), and creating a draft takes the advisory lock of the template first. (A draft *can* exist while another version is in review: the author keeps working on version 5 while 4 is reviewed, because the draft is in the version table, not the locked header; the header lock only stops header writes.
  Submitting 5 waits for the request of 4 to end: there is at most one pending request per record.)
- **Retire while a version is in review:** refused by the lock (a header write); recall or cancel first.

### 4. "Only approvers can edit while it is in approval" (the product owner's rule, 2026-10-21)

**Recommended, with no engine change:** the shipped **Approver** access policy holds the ability `approvals.override-lock` and the update right on the header object. ADR-0148 already gives exactly this person a lock-passing write that is **audited** (`approval.lock.overridden`,
the history of the request). Nobody else (the owner and author included) passes the lock. The cluster's version endpoints apply **the same rule** by asking `RecordLocks.locked` and the ability (and `RecordLocks.overridden` for the audit), so an approver may also correct the grid of the version under review.
**Confirmed by the product owner, 2026-10-21: yes, an approver may change the grid in review, audited.** The trigger of section 2 row 3 has one exception: a session setting `platform.review_edit = on`
(the pattern of `platform.record_recalculation`, V043), set only by that endpoint, and each such change counts in `review_edits` and is audited by counts and the hash, never the text.
**Limit of the recommendation:** the override is by ability, not by task: any holder of the Approver policy may change any template in review, not only the people asked on that request. A *task-scoped* window (only the open-task holders, optionally only named fields) is a small generic addition to `RecordLockGuard` and `approval`
(`RecordLocks.mayEdit(object, record, user)`); it is worth building only if the business wants to name approvers per template outside a policy. It reopens ADR-0148 decision 3 and ADR-0146 ("an approval grants no access") and is **not** recommended for the first release.

### 5. What the engine needs: one generic hook

> **Built in FA4A ([ADR-0187](0187-approval-hooks-the-lock-owners-write-and-the-step-that-is-skipped.md)).** The contract exists with the three calls as decided here, plus a narrow writer for the one write the lock lets through; the cluster's own hook is FA4. One refinement: `onSubmit` is handed the identifier the request will have, so a hook can record it before the request exists.

Two things need a synchronous reaction inside the engine's transaction: moving the version row ([section 2](#2-the-transitions-who-may-what-each-writes) rows 3 to 6) and the first approver's number ([ADR-0176](0176-number-series-and-the-first-approvers-number.md)).
**Decision: a shared-kernel contract `ApprovalHooks`** (implemented by the cluster glue, found by object name, called in the engine's own transaction, a failure rolls the decision back): `onSubmit(context)` (may refuse with a stable reason), `beforeApprove(context, step, approver)`, `onEnded(context, outcome)`.
`approval` calls it; the cluster implements it; `data` and `approval` do not depend on the cluster (the inversion of `RecordLocks`). It reopens what ADR-0153 left out ("no step actions").
**The alternative, events:** the cluster listens to `approval.*` (the outbox) and moves the version row afterwards. It needs no engine change, but the version lags the decision by seconds, a failed handler leaves a version `IN_REVIEW` after an approval, and the number could not be written at all.
Events are kept for what does not have to be atomic (a notice, a refresh of the master list).

### 6. Can the existing process builder express this? What it cannot do (the list the prompt asked for)

> **Built in FA4A ([ADR-0187](0187-approval-hooks-the-lock-owners-write-and-the-step-that-is-skipped.md)):** gap 1 (the step flag `skipWhenNobody`) and gap 2 (the hook, for the number) and gap 7 (`onSubmit`). Gaps 3 to 6 stay as written.

**It can express** (and the organization's administrator builds it in `/setup/approvals`): a process on `InspectionTemplate`; two or more steps; the reviewer and the approver as `RECORD_USER` rules reading two **user fields of the header** that the submitter fills; `allowSelfApproval = false`; `ANY` or `ALL`; a fallback; reminder and escalation hours; entry criteria on header fields
("category and sub-category are set"); outcome literals on the header (not needed here).
**It cannot, today:**
1. **A step that is skipped when its person is not named** (the submitter "chooses the steps": reviewer only, or reviewer and approver). A first step with nobody refuses the submit; a later step with nobody stalls with "attention" until an administrator acts. **Gap: a `skipWhenNobody` flag on a step.** Small; **FA4 prerequisite, not its own sprint** (it touches the process model, its schema, the builder form and the engine's `advance`, about the size of Sprint 25's timing fields).
2. **A step action** (the number): the hook of section 5.
3. **A write to the record at submit** (status "In review"): not needed here (status is derived), so no gap for the cluster; noted.
4. **An outcome that copies a value** (the version number): not needed (active is derived), no gap for the cluster.
5. **A task-scoped edit window:** section 4; optional.
6. **An approver chosen per submit with a variable number of steps:** a fully dynamic chain contradicts ADR-0153; the two named fields cover the stated need.
7. **A check that something outside the record is ready** (the draft grid is valid): the `onSubmit` hook.
Everything else the business described is covered. **Verdict: no separate sprint; one small change (1) and the hook (2) inside FA4's prerequisites.**

### 7. "A retired template is never deleted" (and a fill-in neither)

Today a deleted record sits in the recycle bin and is **purged after 30 days** (`platform.data.retention.recycle-bin`), and the Organization administrator profile holds **every ability** (not object permissions: those come from policies, but an administrator can write a policy that grants themselves delete). Three layers, all recommended:
1. **The shipped policies grant no delete right** on the template and fill objects (Author, Approver, Reader, Filler: create, read, update, never delete).
2. **A retained-object rule, generic** (name: "keep every record of this object"): an attribute of an object definition that the record store enforces in `RecordGate` (delete, restore-over, purge and bulk delete answer a fixed `CONFLICT` "The records of this object are kept."), that the access-policy editor honours (it does not offer or accept the delete right on such an object), and that the recycle-bin retention job skips.
   It is what stops an administrator, because it is not a permission. It is a small generic capability (metadata attribute in the standard-object YAML and the baseline test, `data`, `security`), recommended into **FA1** (the solution framework) because the cluster's objects are declared there.
3. **The database:** a `before delete` trigger on `sheet_version` and `sheet_fill` that always refuses, and a trigger that refuses any update of a non-draft version's grid. (A revoked `DELETE` grant would not hold: the manual migration M001 gives the application role `select, insert, update, delete` on every table and a re-run "repairs the grants" by granting it again, so the protection is a trigger, which a grant cannot undo.) **A delete is normally not a physical delete:** the record store *soft-deletes* by setting `deleted_at` (the recycle bin), so the rule and the trigger must also refuse an **update that sets `deleted_at`** on a retained object, in the code and in the database; a raw soft delete must not slip past a `before delete` trigger.
For `object_record` (shared with every object) a `before delete` trigger and a `before update` check on `deleted_at` refuse rows of retained objects (a platform table of retained object names, loaded from the solution declaration).
**Retire** writes `retired = true` (and the active version `RETIRED`); a retired template and all its fills stay readable to those who had access (shares stay). Removing an organization's data is the tenant lifecycle's job and not a feature of the cluster.

## Consequences

- **Engine changes (FA4 prerequisites):** `ApprovalHooks` and its three calls; `skipWhenNobody`. **Data changes:** the retained-object rule (FA1). **Nothing** changes in the lock guard for the recommended edit rule (section 4).
- **A pointer-free header** means the master list is a cluster endpoint (a join of two reads), not a plain record list; filtering the list by lifecycle uses the version summary, not a header field.
- **Tests (FA4):** a two-instance race on submit and publish; a pending review leaves reads of the active version working (the claim above); only the Approver policy passes the lock; an owner cannot save a grid in review; a draft never reaches a role-share reader; a rejected version keeps its comments; the version immutability trigger (an update of a non-draft grid fails at the database); a retained object cannot be deleted by an administrator.
- **Not run in FA0:** all of the above; the coexistence claim is read from the code, with the lines named above.
