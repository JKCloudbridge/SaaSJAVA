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
(KPI management, Management Review) and the Department group entry are as before.

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

An editor for the score mark beyond one tick (SH6), several sheets in one form (SH7) and a reminder to a
plant head who has not filled their month or not started an Action plan.

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

## Publishing to the plants, the reviewers of a plant and the monthly fill-in (MC2A, ADR-0260)

After Document Management's chain ends (Plant QMS publishes the document to the creator's group) the **creator** of a Management Core document
makes it available to the plants. There is no approval at that step: it is the creator's own act. Open the document, choose **Publish to plants**
(or **Change plants** when it already has some), tick the plants and, for each plant, choose its reviewers: people and groups, at least one for each
plant. Saving sets the whole: a plant ticked is added, a plant unticked is taken back (with its reviewers), and saving with nothing ticked takes the
document back from every plant.

- **Who may:** the person who made the document, and a member who holds the ability **Publish documents to plants** (the Organization administrator
  holds every ability). Nobody else, not even the Publisher of Document Management. The document must have a Revision in use and not be retired.
- **A plant** is a location that has a group of plant heads: a Department group record (Setup) for the location and the department
  **PLANTHEAD**, pointing at a group that has members. A location without one is shown, and cannot be ticked.
- **What the plant heads get:** read access to the document, and a notice and an e-mail (switchable under Documents). They start their fill-in every
  month from the document's "Start a fill-in".
- **Document Management does not undo it:** Unpublish and Retire in Document Management leave the plants as they are; taking the plants back is the
  creator's own act. A new Revision of the document keeps its plants and reviewers.
- **A change of reviewers** reaches the fill-ins sent afterwards. A review that is running keeps the people it already asked.
- **Reviewers should not hold a policy that views every fill-in** (Reviewer, Approver, Observer): such a person sees every fill-in whatever the plant.
  Being asked to review needs a licence and no policy.
- **The approval process of the fill-ins** (Setup, Approvals, on the fill-in object, entry criteria `application = 'management-core'`): step 1 uses
  the rule **Publication reviewers** (no value to choose: the reviewers are the ones the creator chose for the plant of the fill-in), then the last
  step names the top management group. "Any one" or "all" is the step's own setting. A plant with nobody to ask refuses the send in the engine's words.
- **Monthly fill-in:** the plant head chooses the plant (a person of one plant is not asked) and the month (this month first; an earlier month may be
  started to catch up; a later one is refused). One fill-in exists for a document, a plant and a month, and a rejected one is returned and edited,
  never made again. Another plant head of the same plant can read the fill-in and cannot change it. "This month" is the month in UTC.

## The demo (`scripts/demo-setup.mjs`)

For an organization that has the feature it makes the roles of the tree (CEO; Corporate QMS and Reviewers under it; Plant QMS under Corporate
QMS; Department users and Management users under Plant QMS; Plant head under Management users), five groups filled with people (Level 1 Plant
head: Filler, Reader; Level 2 Department QMS and Management QMS: Author, Filler, Reader; Level 3 Plant QMS: Approver, Publisher, Reader; Level 4
Corporate QMS: Approver, Publisher, Reader; Level 5 CEO and top management: Observer), the departments "Level 1" and "Level 2" and a Department group record
for each plant and level, the routing rule `F` with Part A `MC`, and two fill-in processes (one for documents of Document Management alone, one for
Management Core: the reviewers chosen for the plant, then Level 5). MC2A adds a group of plant heads for each plant (**Plant 1 plant heads**,
**Plant 2 plant heads**, each with the Filler and Reader policies) with the Department group records for `PLANTHEAD`, a third plant head, two more
reviewers and a **Review group**, and brings the example Management Core fill-in process up to date if it has the older steps.

## For contributors

No Java or web class names Management Core or any other application (`InspectionArchitectureTest`, `SolutionBoundaryTest`). The set of records
an application means is `RecordSet`; a **narrowed set** carries the application's name and adds one condition to the record store's own question
(`RecordSet.narrow`), and `TemplateAccess.visible` answers "not found" for a document of another application. A new place that lists records of
these objects must narrow them: ask `set.narrow(filter)`. The routing is `DocumentRouting`; the number-time writes are `TemplateIdentityWrites`
(a permitted Save) and `TemplateApprovalHooks` (the first approval). Tests: `RecordSetsTest`, `TemplateApprovalHooksTest`,
`DocumentRoutingRulesIT`, `DocumentRoutingIT`, `ShippedPoliciesIT`, `SolutionOrderAndSharingTest`; frontend `RoutingPanel.test.tsx`.

**MC2A (ADR-0260).** The act is `PlantPublishing` (rule: `PlantPublishRule`, memory: `PlantPublicationStore`, the only class that names the two plant
tables); the approver kind `PUBLICATION_REVIEWERS` asks the shared-kernel `PublicationReviewers`, which `PlantReviewers` answers from the fill-in's
header (its document and plant); the start of a monthly fill-in is `FillIns.start` with `FillPeriodStore` (the claim table) and `FillPeriods` (the
month). The option `monthlyFillIn` follows the document's application (`RecordSets.monthlyFillIn`), like the footer day. Tests: no database:
`RecordSetsTest`, `FillPeriodsTest`, `FillInFieldsInPoliciesTest`, `OnlyThePlantStoresNameTheTablesTest`, `ApprovalProcessRulesTest`,
`ApprovalProcessCodecTest`, `ApprovalArchitectureTest`; real PostgreSQL: `PlantPublishingIT`, `MonthlyFillInIT`, `PlantReviewersIT`,
`PlantPublicationGuardIT`, `PlantPublishingLogsAreCleanIT`, `FillInsIT`; frontend `plantPublication.test.ts`, `PlantPublishDialog.test.tsx`,
`StartFillIn.test.tsx`.

## The score, the limit, the Action plan and top management last (MC2B, ADR-0261)

**The score.** The template marks one formula cell as the score (an author ticks "Use as the score" beside the formula bar of a draft; the mark is a
property of the sheet and travels with every Revision, so a Revision made by uploading a file again must be marked again). The platform calculates it
with the sheet's own formulas at every approval of the review, in percent points with two decimals, and writes `score` and `result` on the fill-in's
header. A score that cannot be calculated stops the approval in fixed words. Nobody types it.

**The limit.** Setup, **Application settings** (ability `application-settings.manage`): a whole percent from 1 to 100, default 85 from the application's
file, per organization. **Strictly below the limit needs an Action plan; exactly the limit does not.** A change applies from the next approval.

**The Action plan.** The Plant activity document names its **Action plan form** (another document of the application) in the plants dialog. When a
fill-in is below the limit its filler gets a notice and a mail (no number in them); a plant head **of the same plant** starts the Action plan from that
fill-in. It is a fill-in of the form, for the same plant and month, linked by the unique `parentFill`, shared read only with the plant's plant heads,
and reviewed by **the reviewers of the fill-in it answers** (never those of the Action plan form). The form need not be published to the plants.

**Top management last.** The last step of both processes is the Level 5 group. The last step of a low-scoring fill-in is refused until its Action plan
has been sent; a fill-in with a good score has no such rule.

**Change requests.** Management users (anyone who can read the published document, in the Document Management chain) ask for a change; a plant head
who cannot change the document cannot. The request carries the application and the next Revision keeps the plants and reviewers.

**What the numbers are for MC3.** `score`, `result`, `plant`, `period`, `status` and the link are indexed fields of the fill-in and in every policy's
read list; the list of fill-ins returns them. A dashboard counts them with the record store's own query.

**Demo.** `scripts/demo-setup.mjs` now makes two processes: Management Core fill-in review (criteria `... AND ISBLANK(parentFill)`) and Management Core
Action plan review (`... AND NOT(ISBLANK(parentFill))`), each the plant's reviewers then Level 5.

## The Home dashboard that compares the plants (MC3, ADR-0262)

The Home tab of Management Core is a dashboard for top management, the Observer and the people who approve; a plant head sees their own plant in it.
It is chosen by the application's file (`options: dashboard: management`), so no code names the application.

**Filters** (in the address, so a view can be shared): **Month** (this, last, or one of the last 24), **Compare over** (3, 6 or 12 months), **Plant**
and **Form**. Months are months in UTC.

**Six numbers** (each opens the fill-in list narrowed to what it counts):

| Tile | Counts | Opens |
|---|---|---|
| Plants that sent their fill-in | plants with a fill-in of the month that is waiting or decided; "n of m" where m is the plants the person may see | `?period=&sent=1&plan=none` |
| Average score | the average of the plants' scores of the month (approved fill-ins only) | `?period=&status=APPROVED&scored=1&plan=none` |
| Plants below the limit | plants whose fill-in of the month was decided to need an Action plan; the line under it says how many have none yet | `?period=&needs=1` |
| Waiting for review | fill-ins waiting before the last step, in every month | `?waiting=review` |
| Waiting for top management | fill-ins waiting at the last step, in every month | `?waiting=top` |
| Action plans open | Action plans not yet approved, in every month | `?plan=open` |

**Six graphs**, each with a "View as table" of the same numbers: the score of each plant for the month (bars on a 0 to 100 scale with the limit drawn
across them), the plants over the months (one line each; a month a plant did not report is a **gap, never a zero**), the average of the plants by month, the
plants ranked with the word of their band, the Action plans by plant (being written or returned, sent, approved) and the fill-ins by month and status.

**The words of the bands** (Setup, Application settings, next to the limit): **Excellent above** (85) and **Good above** (65). A score above the first is
Excellent, above the second up to the first is Good, anything else is Poor; exactly 85 is Good and exactly 65 is Poor. Good must stay below Excellent: the
setting refuses a value that breaks the order. The **limit** (default 85) is another number: an Action plan is needed strictly below it. If the limit was
changed after a review, a bar can be under the limit line while its fill-in was decided to need no plan; the table says "decided under an earlier limit".

**Who sees what** is the record store's: a plant head sees one plant (the whole of "n of m" and the plant list are only the plants they may see), a person who
may view every fill-in (Observer, Approver, a Reviewer policy) sees every plant, and a person who reads no fill-in is shown no section and no zero. A reviewer
with no access policy is asked to decide through the inbox and reads no fill-in here.

**Not narrowed by form:** the waiting tiles and the Action plan numbers, because an Action plan belongs to another form than the fill-in it answers and a
fill-in waits in every month. The page says so when a form is chosen.

**Contributors.** `ManagementHome` (service), `ManagementNumbers` (the arithmetic, pure), `ManagementHomeViews`, `ManagementHomeController`; the web page is
`platform-web/src/solutions/inspection/management/` and `HomePage.tsx` chooses between the two dashboards from the application's `options`. The list reads
its slice from the address (`addressSlice.ts`) and the server filter `waiting` of `POST /fill-ins/query`. The step of a pending request is
`ApprovalDecisions.pendingSteps`. Tests: `ManagementNumbersTest`, `ManagementHomeNarrowingTest`, `RecordSetsTest`, `SolutionLoaderTest`,
`ApplicationSettingOrderTest`; real PostgreSQL: `ManagementHomeIT`, `ManagementHomeLogsAreCleanIT`, `ManagementHomeCostIT`; frontend `model.test.ts`,
`ManagementHome.test.tsx`, `HomePage.test.tsx`, `addressSlice.test.ts`.

## The tabs (SH_UI)

The strip reads: Home, Plant Review, KPI management (coming soon), Management Review (coming soon), Record Control, Change requests, Report, Department group. Plant Review opens the documents page (page key `templates`, so every link, notice and tile still works); Document Control and Plantwise Review no longer exist here. A page's heading and breadcrumb follow the label of the tab that opened it (`useEntryLabel`); the other applications keep Document Control.
