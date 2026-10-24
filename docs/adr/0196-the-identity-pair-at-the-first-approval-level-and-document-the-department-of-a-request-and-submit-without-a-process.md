# ADR-0196: The identity pair at the first approval, Level and Document, the department of a request, and Submit without a process

- **Status:** Accepted
- **Date:** 2026-10-24
- **Track:** Functional (FA6A, demo readiness), with one additive migration (V072), one additive endpoint and one new
  shared-kernel contract
- **Related:** ADR-0148 (a pending request is the lock; the override), ADR-0182 to ADR-0184 (dependent picklists, lists the
  organization owns, number series), ADR-0187 (approval hooks), ADR-0188 and ADR-0189 (the template lifecycle and its
  policies), ADR-0191 (change requests), ADR-0192 (fill-ins), ADR-0115 (who may see a record), ADR-0012 (what is never
  logged)

## Context

The demo flow is: a department user writes a template and sends it; Plant QMS decide how it is numbered and approve;
Corporate QMS approve and publish; the department users fill it in. Four things in the shipped product did not fit:
the author was asked for the category and sub-category (the numbering pair) although the approvers decide them; there was
no way for the author to say what kind of document it is without it becoming part of a number; a change request showed who
asked but not from which department; and a fill-in could be sent only through an approval process, so an organization with
none could not finish one.

## Decision

1. **Two fields for the author, carrying no number.** `InspectionTemplate` gets `level` (controlling) and `documentType`
   (dependent), both picklists whose lists the organization's administrator owns (`organizationValues`, `codeValues`), the
   second restricted by the first exactly like the existing category and sub-category (`PicklistDependencies`, the
   `dependent` stage of the write pipeline). The platform ships a short placeholder list (two levels, four documents); an
   administrator replaces it in Setup. Added additively to the object file and the baseline.
2. **The numbering pair is renamed on screen only** ("Identity category", "Identity sub-category"); the API names stay.
   The shipped **Author** policy edits `name`, `level` and `documentType` and reads the rest; **Approver** edits all of
   them, including the identity pair, the part code and the plan year, and now also holds the ability
   `field-values.add`, so Plant QMS can add a missing sub-category from the form and it lands in the Setup list.
3. **The numbering checks move from Submit to the first approval.** `TemplateApprovalHooks.onSubmit` keeps the retired
   check and the sheet's own checks (an empty sheet, and so on). `beforeApprove` runs the checks (category present and
   with a format, the parts its number is made of, the plan year, a number that is free) **when the template has no number
   yet**, then gives the number. The key is "has no number", not the position of the step, so a process of any number of
   steps works, and one edited in Setup later needs no code. A refusal is the existing hook refusal (409, fixed words, the
   request stays pending, nothing is saved): "Choose the identity category and sub-category (Edit), then approve again."
4. **The approver sets the pair through the override of the lock, not through a new mechanism.** The record is locked by
   the pending request (ADR-0148); a member who holds `approvals.override-lock` may still change it, audited and written
   into the history of the request. The shipped Approver policy holds that ability and the right to edit the fields. The
   limit (it is by ability, not by task: any holder can edit any template in review) is the one ADR-0187 accepted. The
   author, who holds neither, is refused with `RECORD_LOCKED` while the template is in review.
5. **A change request keeps the department of the person who asked.** `DepartmentAudiences.departmentsOf` matches the
   groups of the member, nested ones included, against the `DepartmentGroup` records, reading them as the platform (the
   person may not read that object), and returns the words of the organization's lists at that moment ("Location · Department",
   several joined by a semicolon, none gives no value). The text is written once into the new nullable column
   `inspection_change_request.requester_department` (V072; the row guard now also refuses a change of it) and shown on the
   card. A later move of the person or a relabelled list never rewrites it. It is the organization's text: never logged,
   audited or put in an event or a notice.
6. **Submit without a process.** A new endpoint `POST /api/v1/fill-ins/{id}/submit` (the person who started the fill-in).
   It asks the new shared-kernel contract `ApprovalApplicability` (implemented by the approval engine with the engine's own
   choice of process) whether a process applies. If one does, **nothing changes** and the answer is `REVIEW_NEEDED`: the
   screen opens the ordinary submit popup, so a process made in Setup changes the behaviour with no code, and the way
   without a review is never a way round a process. If none does, `FillSubmission` (sheets) asks `FillOwners` who may
   (the one place), runs `FillLifecycle.submit` with no request (so the same checks as a review: required cells, a row where
   the sheet needs one, the person who started it), and the cluster writes `SUBMITTED` on the header (`FillHeaders`). The
   database already allowed `SUBMITTED` with no request, so no migration was needed for this. A fill-in sent this way is
   final: its cells are read-only for everyone except the counted correction, and it **stays final when a process is made
   afterwards** (a second send is refused by the lifecycle, as for any fill-in that is no longer a draft).
7. **Publish is a list of tick boxes.** The department choice of the publish dialog is a tick box for each department of
   the chosen location; a pair already in the list is shown ticked and disabled; the ticked ones are added together; one
   Publish at the end. The request body and the server are unchanged.

## Consequences

- An author can send a template without the identity pair. It is unnumbered until the first approval; it is not hidden.
- If the first approver approves without the pair, they are told in words and nothing is lost; if they reject, the template
  returns to the author, the lock ends, and the author can change only what the policy lets them.
- A department is derived at the moment of the request; a person in no department has none and the card shows a dash.
- `ApprovalApplicability` is a yes or no and adds no module edge (`inspection` already depends on the shared kernel).
- The test fixtures that made templates as an "author" with the category now give that test person a test policy for the
  identity fields (`TemplateSupport.author`); the real Author, with no more, is `plainAuthor`.

## Alternatives considered

- *Keep the checks at Submit and let the author type the pair:* contradicts the flow (Plant QMS decide the numbering).
- *A new ability or a task-bound edit for the identity pair:* narrower than the override, but a new rule beside the lock for
  no demo need; recorded as an open point in ADR-0187.
- *Derive the department when the card is read:* shows a person's department today, not when they asked, and reads the
  groups on every read.
- *Let the fill-in endpoint start the review itself when a process applies:* the engine's submit needs the member's own
  choice of process and note; the popup that exists already asks for them.
