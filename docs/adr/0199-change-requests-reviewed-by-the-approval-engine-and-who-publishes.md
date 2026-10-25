# ADR-0199: Change requests reviewed by the approval engine, the tab of change requests, and who publishes

- **Status:** Accepted
- **Date:** 2026-10-25
- **Track:** Functional (FA6E, demo readiness), with one additive migration (V075), two additive endpoints of the cluster, one
  additive endpoint of the approval module, one new shipped policy (Publisher) and two narrow shared-kernel contracts
- **Related:** ADR-0148 (a pending request is the lock), ADR-0150 (starting an approval as the platform), ADR-0154 (notices
  are facts), ADR-0187 (approval hooks), ADR-0188 and ADR-0189 (the template lifecycle and its policies), ADR-0191 (change
  requests are two tables, not records), ADR-0192 (the header record of a fill-in), ADR-0196 (the department of a request),
  ADR-0115 (who may see a record), ADR-0012 (what is never logged)

## Context

A change request used to be answered by one decision of "whoever may change the template" (its owner and the Approver
policy). The business flow is different: the department person raises a request; **Plant QMS** accepts or declines it;
**Corporate QMS** accepts or declines it after them (skipped when nobody is in that group); only then is it turned into a new
version, which goes through the template's own approval and is published. Three more things were asked for: a tab that lists
the requests of every template, an asterisk on a template that has requests waiting, and a rule that **Corporate QMS publishes
when it was in the flow** (and Plant QMS only when Corporate QMS was not). The bell had to tell the people who have to act, and
the Details tab had to show the approvals of a template as a history, not only the latest.

## Decision

1. **The request keeps its two tables (ADR-0191 stands) and gets a small record for the engine.** Raising a request makes, in
   the same transaction, a record of the new shipped object `InspectionChangeRequest` (private, kept for good, three fields only
   the platform writes: a name, the template, a status copy) and starts the organization's approval process for that object, as
   the person who asked. This is the pattern of the fill-in header (ADR-0192), not a new engine: a pending approval locks its
   record, a template can have only one pending approval, so the request needs a record of its own. `inspection_change_request`
   gets the nullable column `header_id` (V075), unique and immutable. It is the only link; the text a person typed (title,
   comments, reason) is never copied to the record.
2. **The process is ordinary data.** It is made in Setup, Approval processes, on the object "Change request", like the
   template's: two steps (Plant QMS, then Corporate QMS with "skip when nobody"), the **same groups** as the template process
   but **steps of its own**: editing one process changes nothing in the other. No code changes to add a step, remove one or
   change who is in it. **Every request is decided by its approval and by nothing else**: an organization without a process
   for the object cannot raise a request (409, fixed words that say an administrator must make the process in Setup), so there is no
   second way to decide and no fallback. A process whose first step has nobody refuses the request with the engine's fixed words and
   leaves nothing behind. (A request made before this rule, with no record, can be neither accepted nor turned; it exists only as
   demo data.)
3. **The outcome is mapped by the hook (`ChangeRequestApprovalHooks`, `onEnded`).** Approved becomes `ACCEPTED`, rejected
   becomes `DECLINED` and is final (the department person raises a new request), recalled or cancelled leaves the request
   `OPEN`. A hook is handed no comment (ADR-0187), so the reason an approver typed stays in the approval's own history, where the
   request page reads it for those who may see it; `decision_note` stays empty and is not shown. There is no endpoint that accepts or
   declines a request: the former direct answer was removed (`POST .../answer` is gone; `POST .../{id}/reopen` only puts a done
   request back to accepted when its version was rejected or withdrawn). **Turning into a version needs `ACCEPTED`**. A turned request whose version was rejected or withdrawn goes
   back to `ACCEPTED` (its review already happened); V075 replaces the guard function to allow exactly that move for a request
   that has a record, and to keep the move to `OPEN` for one that has none.
4. **"Open" for the asterisk means still waiting for someone**: `OPEN` or `ACCEPTED` (an accepted request waits to become a
   version). The number is on the master list and on the lifecycle view, counted as far as the caller may see the requests (the
   same rule as the template page: all of a template's requests for a person who may change it, their own for everyone else).
   One statement for the page; only a template that has some is judged.
5. **The list across templates is `POST /api/v1/change-requests/query`**, in the style of the fill-in list: search (the
   title and the name of the person who asked, nothing else), an order the server owns (`date` by default newest first,
   `version`, `status`), a cursor, a bounded page. Who sees which request is **not** decided here: it is
   `ChangeRequestAccess`, asked for each template of a batch (the template must be readable; own requests, and every request when
   the person may change the template). A person who may change no template gets a query narrowed to their own requests. A page
   may be short and still have a cursor; only a missing cursor is the end. `GET /api/v1/change-requests/{id}` opens one request
   without its template (the bell and the inbox have only the request). Typed text never reaches a log, an audit record or an
   error.
6. **Who publishes is an access policy, assigned by an administrator.** The abilities `sheets.publish` (publish and take
   back) and `sheets.retire` move out of the Approver policy into a new shipped policy, **Publisher**, which also lets its
   holder read every template (read and view-all). The owner's rule is "Corporate QMS publishes when it is in the flow; Plant
   QMS only in a flow with no Corporate step; nobody else, except an administrator". That is carried out by giving Publisher to
   the group Corporate QMS, and by giving it to Plant QMS (and taking it away again) when a flow has no Corporate step. The
   Organization administrator holds every ability and so may always publish. The server asks the ability on every act, in the
   lifecycle view (`canPublish`) and in Publish, Unpublish and Retire, so a hidden button is not the rule. **Not chosen:** a
   check of the approval's own record ("did this person hold a task in the last real step"), which would have followed the
   flow by itself but spreads the rule over two mechanisms and cannot be seen or changed in Setup; the owner preferred a rule
   that is visible and changed in Setup, and accepted that an administrator must keep the assignment right when a flow
   changes (the administrator is trained and tests this before handover). Nothing checks that the right group holds Publisher.
7. **No new module edge.** `inspection` does not depend on `approval`. It asks `ApprovalStarter` (to start the review and to
   ask whether a process exists), in the shared kernel, implemented by `approval`, the same
   inversion as `ApprovalTasks` and `RecordLocks`. The hook is a fourth `ApprovalHooks` bean, for the new object.
   `ChangeRequestHeaders` is the cluster's third and last place that takes the platform's door to records (the retired mark, the
   fill-in header, this record); the architecture test names it.
8. **The bell tells the people who have to act, by the notices that already exist.** The engine's own task notices tell Plant
   QMS when the request is raised and Corporate QMS when the first step accepts; the engine's decision notice and the cluster's
   `CHANGE_REQUEST` notice tell the person who asked and the template's owner of the answer. The `CHANGE_REQUEST` notice gains two
   facts, `request` and `tab` (the generic notice code only joins them, it names no tab), so a bell row opens the template on its Change requests tab with that request open; an older notice still opens the
   template. The review record has its own notice destination (the same address), and its read is shared with the template's owner so they can follow the flow. No mail.
9. **The approvals of a template are shown as a history.** `POST /api/v1/objects/{object}/records/approval-history` (the
   approval module, additive) returns the requests of up to 20 records, newest first, in the shape the flow already draws; a
   record the caller cannot read is left out. The Details tab calls it for the template and for the review records of its newest
   change requests, names each template approval by its version (`TemplateLifecycleView.versionReviews`: "New template" for
   version 1, "New version N" after) and each change request "Change requested" (with the version it became), and draws an
   accordion by date, the newest open, each entry with the same `ApprovalFlowView`. Nothing is decided in the browser.
10. **There are two places to read requests.** The application has an entry `Change requests` (page key `change-requests`, reads
    the new object), listed for whoever holds read on the object (Author, Reader, Filler, Approver): the requests of every template. And the
    template's own page has a fourth tab, **Change requests**, after Details, Sheet and Fill-ins: the requests of that one template (the
    same query narrowed by `templateId`, the same rule), and the request page opens inside the tab (`?open=<template>&tab=change-requests&request=<id>`).
    A person sees the rows the rule above gives them in both.

## Consequences

- One new table column, one new guard function body, one new object and one new entry; no new table, so no new row-level-security
  entry. The review record is made by the platform, so the Author, Reader and Filler policies give it `read` only and the Approver
  adds `update`, `view-all` and `modify-all` (the engine needs the right to read, and a withdrawn request is sent again from the
  panel of the record).
- A request that is never answered keeps its record, as a fill-in that is never sent keeps its header.
- Existing organizations are unchanged until an administrator makes the second process.
- **Not done on purpose:** drawing the named places on a read-only grid on the request page (they are listed, with a link to the
  template); saved views of the list across templates; a notice to Corporate QMS beyond the engine's own task notice; copying the
  decline reason into the request (the hook is handed no comment).
- **Recorded risks:** publishing follows the policy, not the flow, so after a flow is edited (a Corporate step added or removed)
  the administrator must move Publisher between the groups, or nobody but an administrator can publish (or the wrong group can);
  in an existing organization the Approver policy loses the publish and retire abilities at the next menu load, until Publisher
  is assigned; the list judges a request by the live rule one template at a time, bounded at 2000 rows read per call.
