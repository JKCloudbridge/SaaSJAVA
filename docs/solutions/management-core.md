# Management Core (key `management-core`)

Decisions: [ADR-0259](../adr/0259-documents-are-made-in-document-management-and-routed-to-applications-by-their-number.md) (documents are
made in Document Management and routed to applications by their number), [ADR-0226](../adr/0226-applications-of-one-kind-on-shared-code-with-their-own-objects.md)
(applications of one kind made of files) and [ADR-0257](../adr/0257-management-core-shared-counters-places-only-publishing-the-footer-day-and-the-owners-of-the-new-format.md)
(the footer day and the options, in part). The fifth tile of the grid. It is made of one file
(`platform-app/src/main/resources/solutions/management-core/solution.yml`) over the code the other applications share.

## What it is

Management Core makes **no documents of its own**. Every document of every application is made in [Document Management](document-management.md)
by the chain that works there: a department or management user creates it, Plant QMS sets the identification, numbers it and approves, Corporate
QMS approves and suggests the plant, Plant QMS publishes it to the group of the person who created it. **The number decides which application the
document also belongs to.** An administrator says in Setup, Numbering, **Application routing**, that Identification `F` with Part A `MC` belongs
to Management Core; the moment Plant QMS gives a document the number `F/MC/01` it also appears in Management Core, and it stays in Document
Management, which shows every document of every application.

Management Core is therefore a **view of Document Management's records, narrowed to the ones that carry its name**: its tabs (Home, Document
Control, Change requests, Record Control, Report) list only the documents, change requests and fill-ins of Management Core, it has **no New
document button**, and who may read what is still decided by the record store (the narrowing chooses, it never grants). The three announced tabs
(KPI management, Management Review, Plantwise Review) and the Department group entry are as before.

| Part | What |
|---|---|
| Documents, fill-ins, change requests | the objects of Document Management (`InspectionTemplate`, `InspectionFill`, `InspectionChangeRequest`), narrowed by the field `application` |
| Number series | Document Management's `document` series; the patterns are set once in Setup, Numbering |
| Plan feature | `management-core` (console name: Management Core), in no plan by default |
| Access policies | those of Document Management (Author, Approver, Publisher, Reader, Filler, Reviewer, Department setup) and the Observer, made available with this application by their own files |

## What is particular to it (all of it data in the solution file)

- `routedByNumber: "true"`: its documents come from Document Management and are chosen by the number.
- `footerDate: "true"`: a document that belongs to Management Core carries, below `number  Rev No.'00'`, the line `Date: 13-Nov-2026`, the day
  the Revision in use was published, **wherever the document is shown** (also in Document Management's view of it): the screen, the print and
  the downloaded file. The screen and the print use the reader's time zone; the file uses `platform.inspection.footer.zone` (default `UTC`).
  A document of any other application has no date line.
- `publishTo: places` and `publishDepartment: PLANTHEAD`: kept for the act that publishes a form to the plant heads (the next session, MC2A).
  In this release the publish is Document Management's and ignores them.

## When a document is published

The creator gets a **notice** and an **e-mail** (the same for every application): "A document you made was published", with a link that opens
it, in Management Core when it was routed there. Each person switches the two channels in their notification preferences (category Documents).
Nobody is told of their own act.

## What it cannot do yet

The creator publishing to the plant heads with reviewers per plant, the monthly fill-in, the score and the 85 percent rule, the approval chain to
the top and the dashboard are the next sessions (MC2A, MC2B, MC3).

## For administrators

- **Switch it on:** give the plan feature `management-core` in the console. Taking it away hides the application and its routing choice at
  once and deletes nothing; a rule that names it stays, shown as unavailable.
- **Route documents to it:** Setup, Numbering, Application routing. A rule needs an Identification (and optionally a Part A). A rule decides the
  documents numbered afterwards: it never moves a document that has its number. The exact pair wins over the identification alone.
- **People and groups:** give the Document Management policies to groups (see the demo setup below); give the **Observer** to the group that
  should see everything and change nothing (top management). Roles alone give no sight of a document; groups and access policies do.
- **Approval processes:** the document's process is Document Management's and the same for every application. A fill-in can have a process of
  its own for Management Core: make it on the fill-in object with the entry criteria `application = 'management-core'`, and give the one for
  Document Management's own documents `ISBLANK(application)`, so that never two fit.

## The demo (`scripts/demo-setup.mjs`)

For an organization that has the feature it makes the roles of the tree (CEO; Corporate QMS and Reviewers under it; Plant QMS under Corporate
QMS; Department users and Management users under Plant QMS; Plant head under Management users), five groups filled with people (Level 1 Plant
head: Filler, Reader; Level 2 Department QMS and Management QMS: Author, Filler, Reader; Level 3 Plant QMS: Approver, Publisher, Reader; Level 4
Corporate QMS: Approver, Publisher, Reader; Level 5 CEO and top management: Observer), the departments "Level 1" and "Level 2" and a Department group record
for each plant and level, the routing rule `F` with Part A `MC`, and two fill-in processes (one for documents of Document Management alone, one example for
Management Core).

## For contributors

No Java or web class names Management Core or any other application (`InspectionArchitectureTest`, `SolutionBoundaryTest`). The set of records
an application means is `RecordSet`; a **narrowed set** carries the application's name and adds one condition to the record store's own question
(`RecordSet.narrow`), and `TemplateAccess.visible` answers "not found" for a document of another application. A new place that lists records of
these objects must narrow them: ask `set.narrow(filter)`. The routing is `DocumentRouting`; the number-time writes are `TemplateIdentityWrites`
(a permitted Save) and `TemplateApprovalHooks` (the first approval). Tests: `RecordSetsTest`, `TemplateApprovalHooksTest`,
`DocumentRoutingRulesIT`, `DocumentRoutingIT`, `ShippedPoliciesIT`, `SolutionOrderAndSharingTest`; frontend `RoutingPanel.test.tsx`.
