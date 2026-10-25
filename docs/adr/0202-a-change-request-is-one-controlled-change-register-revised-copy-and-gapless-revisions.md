# ADR-0202: A change request is one controlled change: the register, the revised copy, one approval chain and gapless Revisions

- **Status:** Accepted
- **Date:** 2026-10-26
- **Track:** Functional (FA8, demo readiness), with one additive migration (V080), additive endpoints of the cluster and of the generic
  sheets module, two small additions to the shared kernel (`SheetDraftRules`, `ApprovalDecisions`) and two to the approval hook
  contract (`SubmitContext.lastStepSkippable`, `DecisionContext.endsRequest`). No new module edge, no new ability, no new policy.
- **Related:** ADR-0199 (change requests reviewed by the approval engine, who publishes), ADR-0200 (a step that asks for a choice, the
  audience it suggests), ADR-0201 (Revisions, the footer row), ADR-0187 (approval hooks), ADR-0186 (sheets and versions),
  ADR-0195 (the reader of spreadsheet files), ADR-0188 and ADR-0189 (publishing), ADR-0148 (the lock of a record), ADR-0012 (what is
  never logged)

## Context

Until now a change to a published template took two separate approvals: one for the change request and, after it was accepted and
turned into a version, another for the version. People kept the request in a register form on paper (the Document Change Register)
and made the new sheet by hand. The organization wants it to be **one controlled change**: the requester fills the register, the
reviewers change a copy of the sheet as the form asks, the approval chain decides once, and the last step publishes the copy as the
next Revision, with the register completed and the numbers right.

The app ships no approval process. The organization makes the process for the object "Change request" in Setup (Plant QMS, then
Corporate QMS, which asks for the audience, then Plant QMS again, which publishes); the platform supplies the rules around it.

## Decision

1. **The register is one set of stored values, drawn as a form and as an Excel page.** The table `inspection_change_register` (V080)
   holds one row for each request. The person who raises the request types the top once (request number, change requested, basis for
   change, horizontal deployment, the format or document that stops being used and its day); the platform writes the facts it knows
   when the request is raised (document name, number, the Revision in use, who and when, the department). The people asked to decide
   the request, and the Organization administrator, may complete the bottom while the request is open (department, approved by,
   signature, effective date, the format obsoleted and its day, and the two Yes/No answers about the server and the master list). It
   is the same row that is changed: there is no history of old wording. The new Revision number and the day it was published are
   written by nobody but the publish. The database holds this whatever the application does (write setting raised only by the store,
   immutable top, bottom only while the request is open, result written once, no delete, forced row level security).
2. **The request number is typed by the requester**: free text of up to 30 characters, no duplicate check, as the organization does
   it on paper today.
3. **The platform fills what is left blank at the publish**: the effective date is the day of the publish (UTC), both answers are
   Yes, "Approved by" lists the real approvers with their days, and the signature box reads "Approved electronically on <day>". What a
   reviewer typed is never overwritten.
4. **The Excel view is a read-only drawing of the same values.** `SheetPages` (generic, no business word) turns a page of cells into
   the grid the screen draws, into an `.xlsx` and back (`read`, which takes only the cells a requester types, from their places). The
   file can be downloaded and uploaded **only while raising**: after that the values are changed in the form. Every cell of the
   file is text, never a formula.
5. **Only one change is in progress on a template.** A new request is refused in fixed words while another is open. Anyone who can read
   a template can click "Ask for a change". With no process for the object the request is refused and the words say what to set up;
   a process whose last step may be skipped is refused too (`SubmitContext.lastStepSkippable`), because the last step publishes.
6. **The revised copy is made on demand by a reviewer.** "Create revised copy" is a button on the request page, not in the dialog of
   the person who asks. Allowed for a person who holds an open task of the request and for a person with the ability
   `access.manage`. The copy is a draft of the sheet **held** for the request (`SheetLifecycle.startHeldDraft`): the people who only
   read the template never see it, only the people asked to decide the request (and the administrator) change it, in the grid or by
   uploading a file that replaces its grid in place (`replace=true`); the Revision in use is never edited. A rejected, declined or
   withdrawn request closes its copy (rejected or withdrawn) and the number it did not use is not lost.
7. **A published template gets no draft by hand.** The shared kernel's `SheetDraftRules` is the seam: the cluster's
   `TemplateDraftRules` refuses a blank draft, a copy of a Revision and an uploaded file for a template that was ever published, in
   fixed words that point to the Change requests tab. The first draft of a new template is not touched, and the copy that a change
   request makes for itself does not ask.
8. **The last approval publishes.** At the last step the approver confirms the Publish window (Location, Departments, and the options
   View only and Allow download, starting from what the earlier step suggested). `ApprovalHooks.beforeApprove` runs inside the
   decision's own transaction and, when `DecisionContext.endsRequest` says this is the last approval, does these things in this
   order: it needs the ability `sheets.publish`, a held copy and a valid choice; it sends the copy through the sheet lifecycle
   (submit, reviewed as approved) and publishes it (`TemplatePublishing.publishRevised`), which shares the template with the audience
   chosen and keeps the options; it stamps the register; it marks the request done. Any refusal undoes the whole decision and the
   approver sees fixed words (the copy is missing, the Publisher access policy is missing, the audience is not valid). The choices
   travel only at a step that asks for one or at the last step. `onEnded` follows the record: done on approval, and on rejection or
   recall it closes the copy and declines the request; an approved end that was not published is a fault that rolls everything back.
9. **Revision numbers have no gaps, and the database says so.** `sheet_version.revision_no` is set once, by the guard, when a version
   first becomes the one in use, as one more than the highest set before it. A copy that is rejected or withdrawn never uses a number.
   A sheet whose versions were in use before this release goes on from the count of them, and a version without a stored number reads
   as its stored version number minus one, as before. The template record carries two platform-written fields, `revision` and
   `lastRevisedAt`, set by the publish (not when the template record is locked). The footer row, the file name, the list and the
   Details tab use the saved Revision.
10. **Readers see the Revision in use and nothing else.** A person who may only read a template sees its active (and retired)
    Revision; the history of Revisions and held copies is for those who may change the template, publish, or override the lock, and for
    the person who holds a task of the request, who sees the copy and nothing else.
11. **What was removed.** The accept step, the "turn accepted requests into a version" step and the reopen act belong to the old
    two-approval way. They are gone from the API, the screens and the hook; the status values stay in the database because rows exist
    and the guard still lists them, but nothing moves a request there. Requests started before this release cannot be finished.
12. **The asterisk moves.** The mark for "a change is in progress" is on the Change requests tab of a template, with a text
    for screen readers, and not on the rows of the list. The list has a Revision column instead of the version.
13. **Nothing typed is logged.** The register is the organization's words. The audit trail carries identifiers, the names of the boxes
    changed and counts; events, notices and errors carry no word of it (`ChangeRequestFlowsLogsAreCleanIT` checks the register
    values too).

## Alternatives considered

- **Keep the manual new-draft button and join the request to it.** Rejected: it keeps two ways to make a Revision and the reviewers
  could publish something the register does not describe.
- **A separate publish act after the last approval.** Rejected: that is the old second approval under another name. The last
  approval is the confirmation and the publish.
- **Store the register on the request record only.** Rejected: that record is locked while the approval runs and the approvers must
  complete the bottom exactly then.
- **Number Revisions in the application.** Rejected: two instances could publish at once; the guard in the database is the single
  place that cannot be bypassed.
- **Let the requester edit the Excel page in cells.** Not built: the form is the one place a value is typed after raising; the page is
  a drawing.

## Consequences

- The organization must make the process for the object "Change request" in Setup (the guide and the manual steps say how), and give
  the Publisher policy to the group with the last word. Without them no request can be raised or published.
- Fill-in and report screens still show the Revision as the stored number minus one, which can be off by the number of rejected
  copies in between; the template pages use the saved Revision.
- Reviewers who cannot read a template cannot open the request page through the template; the review record's task link is the way.
- The register cannot be edited in the Excel view. The round trip of a spreadsheet upload for the sheet itself still loses the
  header-band and input-cell structure (ADR-0195).
- Tests that made a second draft of a published template by hand now go through a change request (`NextRevision` in the test support).
