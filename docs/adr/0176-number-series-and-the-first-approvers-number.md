# ADR-0176: Number series, and how the first approver's number gets written

- **Status:** Proposed
- **Date:** 2026-10-21
- **Sprint:** FA0 (Functional track, spike; no product code)
- **Related:** [ADR-0096](0096-auto-number.md) (auto-number), [ADR-0111](0111-uniqueness-of-field-values.md) (uniqueness through a mask), [ADR-0148](0148-record-locking-while-an-approval-is-pending.md), [ADR-0139](0139-background-writes-the-one-reviewed-path.md), [ADR-0153](0153-what-sprint-24-leaves-out-and-why.md), [ADR-0174](0174-template-versions-and-approval-together.md), [ADR-0177](0177-dependent-picklists.md)

## Context

A template gets a number like `PL/DD/0001`: the first two parts come from two dependent picklists (category and sub-category), the last part counts up **per pair**. The product owner decided (2026-10-21): **the first approver assigns the number, then approves**, in the first approval step; so a template rejected at the first step never takes a number;
the number belongs to the **template** (a second version keeps it); a template rejected at a later step keeps it; a retired template's number is never reused; and (later the same day) **only approvers can edit a record in approval, nobody else**. The question the prompt put: the record is locked while the approval is pending, a background write to it is refused (`RECORD_LOCKED`),
and an approver holds no access from a task, so **neither a workflow nor the approver's own rights can write the number**. It has to be made possible.

## What exists

`AutoNumbers`: one row per organization, object and field in `auto_number_counter`; **one statement** (`insert ... on conflict ... do update set next_value = next_value + 1 ... returning`) takes a number; the row stays locked until the surrounding transaction commits (two instances never get one number);
the prefix is `[A-Za-z0-9_-]{0,10}` (no `/`), the counter is **per field**, not per pair, and it runs **only on create** inside the write pipeline. `approval` offers one public interface, `Approvals.startByPlatform`; the engine's decision methods are package-internal.

## Decision

### 1. The series (generic, FA2, no cluster word)

**`NumberSeries`** in the module `data` (next to `AutoNumbers`, which it generalises; `approval` already depends on `data`, so no new module edge). A series is declared in a bundle (`numbering.yml`), not by organizations in the first release:

```
key: template
object: InspectionTemplate
field: number                         # a unique text field, written by the platform only
format: "{field:category}/{field:subCategory}/{seq:4}"
```

**The format language is closed.** Tokens: `{field:<apiName>}` (the **stored value** of a picklist or text field of the record, which must match `[A-Za-z0-9_-]{1,10}`), `{seq:N}` (the counter, zero-padded to N digits, 1 to 9), and literal text from `[A-Za-z0-9/_-]` (at most 10 characters).
At most four tokens. No clock, no arithmetic, no expression, no executable text; the loader checks the fields exist and are of a type that fits, and a value that does not fit the pattern refuses the numbering with a fixed reason (never the value).
A number longer than its width is not cut (as `AutoNumbers`).

**The counter is per scope.** The scope is the resolved `{field}` tokens (here `PL/DD`); the table **`number_series_counter`** (tenant-scoped: `tenant_id` default `platform_current_tenant()`, forced row level security, the guard trigger, the tenant-first index, an entry in `TenantScopedTables`)
holds `(tenant_id, series_key, scope_key, next_value, version)` with a partial unique index; taking a number is the same single upsert as `AutoNumbers`, `returning next_value - 1`.
**The row lock is held until the transaction ends**, so two approvers numbering the same pair queue (a few milliseconds each: approvals are rare), and different pairs do not wait for each other.

**Never reused.** The counter only moves up: a retire, a rejection at a later step and a delete (there is none: [ADR-0174](0174-template-versions-and-approval-together.md) section 7) do not give a number back. A number taken in a transaction that **rolls back is never issued** (it exists only inside that transaction), so the next taker gets it; that is not a reuse.
Gaps are allowed (a template rejected at the first step takes no number, but one rejected later keeps its own).
**The backstop** is the database: the `number` field of the header is declared **unique**, which the platform already enforces through the mask and a partial unique index ([ADR-0111](0111-uniqueness-of-field-values.md)); a second `PL/DD/0007` fails the write, whatever the application did.
**The preview** shows only the prefix ("PL/DD/…"), never the sequence: showing it would mean taking it, and with it the lock.

### 2. Who writes it, and when: the options compared (recommendation first)

| | What it is | Code | Tests | Verdict |
|---|---|---|---|---|
| **(b) A generic hook of the approval engine** (recommended) | the shared-kernel contract `ApprovalHooks` ([ADR-0174](0174-template-versions-and-approval-together.md) section 5): the engine calls `beforeApprove(context, step, approver)` inside the decision's transaction; the cluster's implementation, on **step 1** and a **blank number**, takes the number from the series and writes it to the header | the contract (about 30 lines); the engine's call (about 15 lines in `decide`); a write the lock lets through (below); the cluster's implementation (about 40 lines) | atomicity (a failing hook rolls back the decision and the counter), a race of two approvers, the lock refusal for everyone else, a reject at step 1 leaves no number, ALL-completion | **chosen**: atomic by construction, no new button, no new public call, no new module edge, and the ordinary **Approve** button does the whole job |
| (a) The cluster's own "Number and approve" endpoint | one endpoint checks the caller holds the open task of step 1, takes the number, writes it, then calls the engine's decision | a new **public** approval contract to decide as a member (the engine's methods are internal), plus the lock exemption **and** a way to stop the ordinary Approve button from approving step 1 without a number (which is a hook again) | as (b) plus the second path | acceptable fallback; more moving parts, two ways to approve step 1 |
| (c) A lock exemption alone | let an approver write fields of a locked record | the exemption in `RecordLockGuard` | | **no**: it lets a person type a number; the number must come from the series |
| (d) Keep the number outside the locked record | a table the lock does not cover; the page reads it from there | a second source of truth, a join for every list and search by number, and the same "approve without a number" hole | | no |

**The write the lock lets through.** The lock *is* the pending request ([ADR-0148](0148-record-locking-while-an-approval-is-pending.md) decision 7), so its owner, the engine, may write while the request is pending: `BackgroundWrites` gets a source `approvalHook(requestId)` and `RecordLockGuard.requireOpenForPlatform` accepts it **only when `requestId` is the pending request of that record**.
Everything else about a background write is unchanged (the full write pipeline, no permission stages, never a value in a log, the audit record by field name). The field is **not editable by any member** (no field permission grants edit): only the engine's hook writes it.
This is the narrow, audited path the prompt asked for, expressed as "the lock's owner may write", not as an exemption for approvers.

### 3. The other points the prompt asked to settle

- **If the numbering succeeds and the approval then fails:** one transaction, so neither (the decision, its history, the hook's write and the counter commit together or not at all).
- **Only the first-step approvers "see the numbering control":** there is no control. The first approval of step 1 gives the number; a person sees a line on the page ("Approving this gives it its number, PL/DD/…") only while the step is theirs. With `ALL` completion at step 1, the **first individual approval** numbers it; later ones find the number and skip.
- **The number belongs to the template:** it is a header field; version 2 and 3 never touch it. **A retired template's number** stays on its header, forever.
- **Approvers fix mistakes first:** an approver who holds the override ability may correct category or sub-category before approving ([ADR-0174](0174-template-versions-and-approval-together.md) section 4), because the number uses what the header says at that moment.
- **An approver without the override** can still approve (the hook writes as the engine); they just cannot edit.
- **Module edges and `docs/modules.md`:** **none new.** `approval` and `data` gain the contract and the source; the cluster module implements the shared-kernel contract and depends on `data`, `metadata`, `security` and `licensing` like any business module. The generic modules gain two lines in their `package-info` text and `docs/modules.md`; the cluster module and `solution` are new rows.

## Consequences

- FA2 builds `NumberSeries`, `number_series_counter` and the dependent picklists; **FA4** wires the hook into the engine and the cluster (the engine change is the prerequisite listed in ADR-0174).
- Tests, written in FA2 and FA4: two **instances** numbering one pair at once (numbers 1..N exactly once; the unique index never fires); a failure inside the transaction leaves the counter where it was; the format language's hostile corpus (a token with markup, a value of the wrong shape, too many tokens); a rejected-first-step template has no number; a rejected-later template keeps its number; a retired template's number is never issued again; an isolation test for the counter table; a leak test (a number is the organization's text: facts only in logs and audit).
- If the product owner later wants the approver to *choose* the pair or type a suffix, that is a different design (a deliberate act); nothing here prevents it.
