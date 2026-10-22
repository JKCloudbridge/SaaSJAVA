# ADR-0188: The template lifecycle: the cluster module, the moves of a version, publishing as a share, retiring and the master list

- **Status:** Accepted (the product owner delegated every technical choice on 2026-10-22 and answered the seven questions of the plan with "your recommendation"; the plan and its answers are in the sprint document)
- **Date:** 2026-10-22
- **Sprint:** FA4 (Functional track)
- **Related:** [ADR-0174](0174-template-versions-and-approval-together.md) (the model; sections 2, 3 and 4 are built here), [ADR-0176](0176-number-series-and-the-first-approvers-number.md) (section 2 is built here), [ADR-0178](0178-shares-to-roles.md) and [ADR-0185](0185-shares-to-roles-the-typed-grantee-and-the-choice-of-roles-and-groups.md) (shares), [ADR-0180](0180-names-the-module-the-objects-and-the-words.md) (the module `inspection`), [ADR-0184](0184-number-series-declaration-formats-and-counters.md) (**amended in section 3 below**), [ADR-0186](0186-the-sheets-module-versions-the-owner-seam-and-the-request-size.md) (section 8 is answered here), [ADR-0187](0187-approval-hooks-the-lock-owners-write-and-the-step-that-is-skipped.md) (the hook), [ADR-0139](0139-background-writes-the-one-reviewed-path.md), [ADR-0189](0189-who-sees-a-template-the-fixed-private-default-abilities-and-field-rights-of-shipped-policies-the-approvers-edit-and-the-open-task-read.md) and [ADR-0190](0190-department-groups-the-object-group-and-the-mapping.md)
- **Evidence:** `TemplateLifecycleIT`, `TemplatePublishingIT`, `TemplateNumberingIT` (two real instances), `TemplateAccessIT`, `TemplateListIT`, `RecordSharesIT`, `TemplateApprovalHooksTest`, `InspectionArchitectureTest`, `NumberFormatTest`, `SheetListCostIT` and `TemplateListCostIT`, and the web tests of the master list, the publish dialog and the sheet workspace

## Context

FA3 gave a template a header record and a sheet kept in versions, and its database already enforces the lifecycle of a version. FA4A gave the approval engine a hook and an optional step. FA4 makes the lifecycle real: a creator submits, approvers approve step by step and the first approval gives the number, an approver publishes to the people of a department, takes it back, retires it, and everybody finds templates in a master list that shows each person only what they may see. The decisions of ADR-0174 and ADR-0176 stand; this record settles how they are built and what building them decided beyond them.

## Decision

### 1. The split: what is generic and what is the cluster's

| Part | Where | Why there |
|---|---|---|
| The moves of a version (submit, the end of a review, publish, unpublish, retire, a summary by owner) | `sheets`, public interface `SheetLifecycle` (`SheetMoveRefusal` for a refusal) | Keyed by the owner (an object and a record); no cluster word. The database refuses every move the lifecycle lacks whoever asks |
| Who may read or change a sheet, the lock, the correction in review | `sheets` `SheetOwners`, nowhere else | Unchanged seam of FA3; two questions are added (ADR-0189 sections 4 and 5) |
| A read share of a record from Java | `data`, public interface `RecordShares` | The share is the record store's; a module needs a way to make one for an act it has decided itself |
| The hook of the engine, the number, the audience, the master list, the lifecycle view, retire | `app.platform.inspection` (new module, a leaf) | The cluster's glue. Nothing generic depends on it (`InspectionArchitectureTest`) |

`SolutionBoundaryTest` is unchanged except that one migration file (`V066`) is listed among the places the cluster's words may be. The cluster's endpoint records live in its own module, not in `platform-api-contract`, because the contract module is scanned for the cluster's words.

### 2. The hook, as built (`TemplateApprovalHooks`)

It serves `InspectionTemplate` and may write one field, `number`.

- **`onSubmit`**: the template must not be retired. If it has no number yet it must be able to get one: a category (else "Choose the category..."), a category that has a format, and every part its number is made of (else "Fill in the sub-category and the part code before sending ... its number is made from them", the parts named by what they are), a plan year that is four digits when one is typed, and, for a number made of typed parts alone, **a number nobody else has** (an exact lookup; the unique index remains the backstop). Then the one draft goes to review under the request (`SheetLifecycle.submit`), which checks the draft again as it is now (valid by today's limits, not still a blank start) and refuses in the validator's own words with the place. Every refusal is an `ApprovalHookRefusal` with fixed words that never repeat what an author typed.
- **`beforeApprove`**: it reads the **record**, not the position of the step. A blank number is taken from the series and written through the engine's narrow writer; a number already there ends the call. A template rejected at the first step therefore never reaches it, one rejected later keeps its number, and a skipped first step makes no difference (ADR-0187 section 6). If the number cannot be made now (an approver corrected the category in review) or the store says it is taken, the approval is refused with fixed words and the decision, the counter and the history are undone (`TemplateNumberingIT`).
- **`onEnded`**: the version the request reviewed becomes approved, rejected or withdrawn (a recall and an administrator's cancellation are both "withdrawn"). Approving does not publish.

### 3. The format language: a number can be made of typed parts alone (amends ADR-0184)

ADR-0184 said a format has exactly one `{seq:N}`. Four categories of the first customer end in a part the author types (a part code, a year), so the language now allows **no counter when the format reads at least one field** (`CP/{field:subCategory}/{field:partCode}`, `BP/{field:planYear}`). A format with neither is still refused. Consequences, all in `NumberSeries`: `next` takes no counter row for such a format; `known` gives the whole number so that a clash can be found before anything is taken; `missingParts` names the fields a record still has to fill in; `prefix` gives the whole number when there is no counter (the preview then says it is complete). The fields are `partCode` (text, up to 10) and `planYear` (text, four digits, checked by the cluster); they are required for those categories only, by the submit hook, because a shipped object cannot make a field conditionally required. Once a template has its number, the category and parts it was made from can still be edited by anyone with the right; the number does not follow. This is a known limit (the header could be given a rule "fixed once numbered" by a later generic step) and is listed in the sprint document.

### 4. Publishing is a read share of the header (`RecordShares`)

`RecordShares.grantRead(object, record, grantee, actor)` and `revoke(...)`: same table, same reader of shares and same rules as the endpoint (the grantee is a member, a public group or a role of this organization; one share per grantee; the owner is never a grantee; the most shares of a record), audited by the kind of grantee and the level. Properties that matter:

- **It never overwrites.** The insert is `on conflict do nothing`: a share made by hand with the share panel, of any level, is left as it is and reported as `ALREADY_SHARED`, so a caller that wants to take back only what it gave can tell. (The first idea, the endpoint's upsert, would have lowered a hand-made read-and-write share to read.)
- **Only read can be given.** The level is not a parameter.
- **It works on a locked record.** A share changes who reads a record, not the record (ADR-0148 decision 2), so a template in review can be published (`TemplatePublishingIT`).
- **Only the cluster may call it** (an architecture test, with a fixture class that proves the rule fails).

### 5. The memory of a publish (`inspection_publication`, V066)

One row for each group a template is or was published to, with the version, the location and department codes the group stood for, **whether the publish made the share itself** (`made_share`), who and when. Rows are never deleted; a revoked row never changes again; what a row says about its template and group is immutable (database guards, `PublicationGuardIT`). One live row per template and group (a partial unique index).

- **Publish** resolves every pair first (ADR-0190), then, all or nothing: moves the version (`ACTIVE` replaces the previous one, which becomes `SUPERSEDED`, in one transaction), revokes the groups that were published to and are not asked for any more, and grants the new ones. A publish that names the version already in use only changes the audience; the dialog starts from the audience the template has.
- **Unpublish** makes the version in use `APPROVED` again and removes **exactly the shares the publish made**, never a share given by hand.
- **A group removed afterwards** takes its shares with it (the existing removal of shares of a removed group); the publication row stays and the lifecycle view says the group is no longer available.
- Ability **`sheets.publish`** (ADR-0189), a different thing from approving. The cluster holds its own lock per template (an advisory lock) so that two approvers publishing at once are decided one after the other; the second finds it done.

### 6. Retiring

Ability **`sheets.retire`**. The header is marked `retired` through the platform's own write (`BackgroundWrites.Kind.SOLUTION`, a third reviewed caller after workflows and approvals), then the version in use ends as `RETIRED`. The field is platform-written (the loader now allows a yes/no field to be, besides a text one), so nobody can set or clear it by hand, not even the administrator. The header write is refused while a request is pending (the lock), which is the rule "refused while a version waits for approval", said in plain words. Nothing is deleted, the number stays (the counter only goes up; a typed number stays in the unique index), the publications stay and the people they reach keep reading it, and a retired template cannot be sent for approval again.

### 7. The master list

`GET /api/v1/templates`: one page of headers read through `Records.query` (so the object permission, the private default, the shares and the field permissions apply unchanged and two people see different lists from the same data), then **one grouped statement over the versions of exactly those ids**, which never selects a grid (`SheetLifecycle.summaries`). Filters: search (name or number), category, sub-category, retired, and the state of the template as one word; order: name, number or newest; the cursor of the record store. The state filter cannot be part of the header query (the header holds no pointer to a version, ADR-0174 section 1), so it is applied to the page after it is read; a page can then hold fewer rows than asked while the cursor goes on, and the page says so. Cost is stated in the sprint document from `TemplateListCostIT` (an explain on 2,000 templates of 200 versions).

### 8. What is deliberately not done

| Not done | Why |
|---|---|
| Mail to the people a template is published to | not required in FA4; a notice would be facts only (ADR-0154) and is left for when a requirement names it |
| A rule "category and parts are fixed once numbered" | a generic header rule; listed as a limit |
| A task-scoped edit window for approvers | reopens ADR-0148 decision 3; the ability covers the owner's rule |
| The master list filtered by state inside the query | would need a join or a pointer in the header |
| Several approval processes chosen by category | the process is the organization's; entry criteria already exist |

## Consequences

- ADR-0174 sections 2 (rows 3 to 9), 3 and 4, and ADR-0176 section 2, are built. ADR-0184's rule "exactly one counter" becomes "at most one, and at least one field when there is none".
- New: module `inspection` (edges `sharedkernel`, `tenant`, `security`, `metadata`, `data`, `sheets`), `SheetLifecycle`, `RecordShares`, `NumberSeries.known` and `missingParts`, `BackgroundWrites.Kind.SOLUTION`, the table `inspection_publication` (V066), and six endpoints under `/api/v1/templates`.
- Changed: the hook's own words are the only place the cluster speaks to an author at submit; every refusal has a stable key (`template_retired`, `category_needed`, `category_not_numbered`, `number_part_needed`, `plan_year_invalid`, `number_in_use`, `not_numbered`, `template_missing`, `sheet_*`).
- A template and its versions are never deleted by anything this record adds.
