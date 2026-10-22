# ADR-0191: Change requests on a published template: tables of the cluster, a live rule for who sees what, anchors by identifier, and the next version from the one in use

- **Status:** Accepted (the product owner answered the four planning questions on 2026-10-22: the recommendation for visibility, the version in use as the base of the new version and tables of the cluster; the answer on notices was "build it now", which departs from the recommendation and is built in section 7; every other technical choice was delegated)
- **Date:** 2026-10-22
- **Sprint:** FA5 (Functional track)
- **Related:** [ADR-0172](0172-the-grid-component-and-the-adapter.md) and [ADR-0173](0173-the-canonical-grid-json-and-its-limits.md) (the grid and its stable identifiers), [ADR-0174](0174-template-versions-and-approval-together.md) (the model; the parts built here are named in section 9), [ADR-0180](0180-names-the-module-the-objects-and-the-words.md) (the names; **amended in section 1**), [ADR-0186](0186-the-sheets-module-versions-the-owner-seam-and-the-request-size.md) (the sheets module), [ADR-0188](0188-the-template-lifecycle-the-cluster-module-publishing-and-the-master-list.md) and [ADR-0189](0189-who-sees-a-template-the-fixed-private-default-abilities-and-field-rights-of-shipped-policies-the-approvers-edit-and-the-open-task-read.md) (the lifecycle and who sees a template), [ADR-0154](0154-the-notification-model-in-app-notices.md) to [ADR-0156](0156-preferences-and-the-floor.md) (notices)
- **Evidence:** `ChangeRequestsIT`, `ChangeRequestsAccessIT`, `ChangeRequestGuardIT`, `ChangeRequestRacesIT` (two real instances), `ChangeRequestFlowsLogsAreCleanIT`, `ChangeTextTest`, `ChangeAnchorTest`, `ChangeRequestStatusTest`, and the web tests of the annotate mode, the composer and the review list

## Context

A person in a department reads a template that was published to them and finds something to change. They may not edit it. The business wants them to **ask**, pointing at a row, a column or a cell of the version they read; the owner to see the requests, talk, accept or decline, and when agreed make the next version; and that version to be approved again like any other. A rejected version must keep its comments. FA4 built the lifecycle; FA5 builds this on top of it without changing any version it reads.

## Decision

### 1. Two tables of the cluster, not two shipped objects (amends ADR-0180)

The plan and ADR-0180 named `TemplateChangeRequest` and `TemplateChangeComment` as objects with a master-detail link. Reading the code first showed why they should not be records of the record store:

- **A detail record inherits nothing from its master.** Record security is the owner, the shares and the policies of the object; there is no "controlled by the parent". Every request and every comment would need shares to the other side, and `RecordShares` can give read only, so the owner could not reply on someone else's request.
- **The visibility rule of the product is live**: a request is seen while its requester can still read the template. A share cannot say that; it would stay behind when access ends.
- **The place of a comment has to be checked against the grid** of the version read. Nothing runs when an ordinary record is created, apart from automation with literal values, so the ordinary create would have had to be closed and replaced by a cluster endpoint anyway.

So the two are tables of the cluster, `inspection_change_request` and `inspection_change_comment` (V067), in the module `inspection`, next to `inspection_publication` (V066), reached by endpoints under `/api/v1/templates/{id}/change-requests`. The names of the concepts stay (a request, a comment on it). Consequences for ADR-0180: the two words are not objects; the shipped policies need no right and no field right for them; the screens are the cluster's own.

### 2. Who sees and who answers: one class, asked each time (`ChangeRequestAccess`)

- A person reaches the requests of a template **only while the record store lets them read the template** (the same answer as for the template itself). Everything else is "not found", the same words as for a template that does not exist.
- **A person sees their own requests.** A person who **may change the template** (its owner, and everybody who holds *modify all* on templates, which the Approver policy gives) sees **every** request. Other readers, and other authors who do not own the template, see none.
- **Asking needs only the right to read** a version that is in use or was. No ability, no object right, no change to the five shipped policies. Answering (accept, decline, reopen, turn into a version) is for those who may change the template.
- **The rule is live**: nothing is written to grant it, so there is nothing to go stale when a template changes hands or access ends. A request grants nothing and changes nothing of the template, its active version or any other version.

### 3. The anchor: the identifier of the grid, checked against the version that was read

Rows (header rows included) and columns share one namespace of identifiers in a grid (ADR-0173). A place is **no identifier** (the template as a whole), **a row**, **a column**, or **both (a cell, whether or not the cell holds anything**, since an input cell is not stored). What is stored per comment: the two identifiers, and **a label snapshot** (`anchor_label`) that the server writes from the grid at the time, so that the owner can read "column Torque" after the column has gone. A request pins the number of the version read.

- **Checked** against that version by the generic `SheetLifecycle.layoutOf` (the identifiers and labels of one version, read through the one statement that reads a grid; no content). The version must be one a reader may read (in use, replaced or retired): a draft answers like a version that does not exist.
- **Survives a new version**: the identifier is the same place in a later version. A request list asks the version in use whether the place is still there; a comment whose place is not shows **"no longer in this version"** with the label it had.
- **Known limit**: identifiers are random strings made by the browser (about 50 bits) and are "never reused" by convention (ADR-0173); the server does not enforce that across versions. A collision that moved a comment to another row would need a client that reused a removed identifier on purpose.

### 4. Turning requests into the next version

`POST .../change-requests/turn-into-version` takes up to 20 open or accepted requests and makes **one draft** with `SheetLifecycle.startDraft`:

- **The base is the version in use now** (the latest approved when none is in use), **not the version each person read.** A copy of an older version would silently undo approved changes made since. The comments find their places by identifier either way.
- **A draft is never deleted**, so if one exists the requests are **linked to it as it is** (`created: false`) and nothing is copied. The owner edits the draft in the sheet.
- **The requests become `DONE` and name the version** (`resulting_version`). The status of that version is read live when a request is shown ("Version 3 (in review)").
- The draft then goes through the existing submit and approval. **A request never changes a version**: only the owner's own act makes a draft.
- **A rejected or withdrawn version keeps its comments**: they belong to the request, which only moves back to `OPEN` by an explicit **reopen**, allowed only when the version it became was rejected or withdrawn.
- **Two instances turning one request**: the template's lock (`change-requests:<tenant>:<template>`), taken before the sheet's own lock, makes them wait in turn; the second finds the request `DONE` and is told so (`CONFLICT`). The answer is idempotent on its own status check, not on a timer.

### 5. Statuses, text and limits

`OPEN` → `ACCEPTED`, `DECLINED` (a reason is required) or `DONE`; `ACCEPTED` → `DECLINED` or `DONE`; `DONE` → `OPEN` (reopen); `DECLINED` is final. The database enforces the same table (V067). Comments are added only while a request is open or accepted, by the requester or by someone who may change the template.

- **Text is plain**: markup (`<` `>`), control characters other than a line break and marks that reverse the direction of text are refused; empty or over the limit is refused; a refusal never repeats the text.
- **Limits** (`platform.inspection.change-requests.*`, checked under the template's lock because none of these rows is ever deleted): 50 comments a request, 10 open requests a person on a template, 200 requests a template, 2,000 characters a comment, 120 a title, 500 a reason, 10 comments a request starts with, 20 requests turned at once. The database holds higher ceilings (4,000, 200, 1,000).
- **Nothing is deleted**, by the application or the owner of the database; a comment is never changed (V067 guards, `ChangeRequestGuardIT`).

### 6. What is never written down

The title, a comment, a reason and the labels of a place are the organization's text. They are shown to the people who may see the request and are **never written to a log, an audit record, an event, a notice or an error message** (`ChangeRequestFlowsLogsAreCleanIT`). The audit trail carries identifiers, version numbers, counts and the kind of place (`inspection.changerequest.created|commented|accepted|declined|reopened|turned`).

### 7. Notices (departs from the recommendation, on the product owner's answer)

A new kind `CHANGE_REQUEST` in a new category `REQUESTS` (V068 widens the two lists the database checks; the platform's own text, not writable by an administrator). Facts only: the event (`requested`, `commented`, `accepted`, `declined`, `done`), and the application and entry that open the template, so the notice leads to the template's page. Who is told: the **owner** when a request arrives and when the requester comments; the **requester** when someone else comments and when the request is accepted, declined or turned into a version; **nobody about their own act**. A person may switch the category off (it is not a floor). The mail switch of the category has no effect yet: no mail is sent for these notices.

### 8. The browser

Annotate mode is **generic** (the sheets components know no business word): `SheetWorkspace` takes an optional `annotate` for the one version it names, which must be read-only and not a draft. The grid stays read-only; the places that have notes are marked (an accent edge on a cell or a column label, a line under a row, and the count in the words of the cell for a screen reader); a click on a column label stands on that column; Enter on a cell asks for the box of a note. What a note is, who may write one and where it goes is the cluster's panel (`AnnotatePanel`), drawn under the grid, and the review list (`ChangeRequestsSection`). There is **no row header** in the grid, so a row is chosen by standing on any cell of it and pressing "Comment on this row". The browser decides nothing: the flags are the API's and every request is decided again.

### 9. What of ADR-0174 and the plan is built, and what is not

Built: the request, the comment, the anchor by identifier, review by the owner, the new version from a request, "a rejected version keeps its comments". **Not built, on purpose:** a requester withdrawing their own request (the owner can decline it); mail to the people a template is published to; a count of open requests in the master list; a row header in the grid; fill-ins and a spreadsheet file import (the following set).

## Consequences

- New: tables `inspection_change_request` and `inspection_change_comment` (V067); the notice kind and category (V068); endpoints under `/api/v1/templates/{id}/change-requests`; `SheetLifecycle.startDraft`, `statusOf` and `layoutOf`; settings `platform.inspection.change-requests.*`.
- Changed: the preferences page lists one more category (four categories, eight switches); `NoticeText` has the platform's words for the new kind.
- No new module edge: `inspection` uses the shared-kernel notice and directory contracts it already may.
- A template, a version and a request are never deleted by anything this record adds.
