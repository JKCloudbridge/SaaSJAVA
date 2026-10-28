# The Quality Management System solution (key `inspection`, first called Audit maintenance)

> **Since FA7 the screens say *Revision*, written with two digits starting at 00** (the stored number minus one). Where this page says
> "version" of a sheet or a template, read *Revision*; the stored numbers, the API and the code keep the word "version". See the last section.


The first platform-shipped solution (Functional track). This page is for two readers: the **platform administrator and organization administrator** who switch it on and use it (section 1), and the **contributor** who extends it in the next sprints (section 2). The framework behind it is in [ADR-0181](../adr/0181-the-solution-framework.md); the decisions about the cluster itself are ADR-0172 to ADR-0180. The word "audit" in code still means the audit trail: the cluster is called `inspection` in code and "Audit maintenance" for people (ADR-0180).

## 1. For administrators

### What exists in this release (FF1): fill-ins

A person can now **fill in** a template that was published to them. The decisions are in ADR-0192.

1. **Start.** On the page of a template that has a version in use, a person who holds the **Filler** policy and is in a department it was published to sees **Start a fill-in**. The fill-in is made on the version in use and stays on it for ever. A template with no version in use, or a retired one, starts none.
2. **Fill.** The fill-in opens in the **Fill-ins** page of the application (also reachable from the template: **See the fill-ins of this template**). Only the cells the template marks as input take a value; each value is checked by the type of its column; rows can be added where the template allows. **Grid view** on a wide screen, **Form view** on a phone. Save as often as needed; a half-done fill-in can be saved.
3. **Send and review.** The ordinary **Submit for approval** of the fill-in's page. A required value missing, or no row where the filler adds the rows, is refused in plain words. The administrator builds the approval process on **Fill-in** (`/setup/approvals`); a rejection returns the fill-in to its filler, an approval fixes it.
4. **Who sees which.** The filler sees their own. **Approver** and the new **Reviewer** policy see every fill-in of the organization. A person asked to review reads that one fill-in.
5. **After it was sent.** Only the people who hold the **Approver** policy can correct it; each correction is counted and recorded without its content.

**What the organization's administrator sets up for fill-ins.** Give **Filler** to the people who fill in (they must also be in a department the template is published to), **Reviewer** to the people who only need to see every fill-in, and build the approval process on Fill-in.

### What exists in this release (FA5): change requests

A person who can read a published template may now **ask for a change** without being able to edit it.

1. **Ask.** Open the template (Quality Management System, Document Control). Under the sheet, **Ask for a change**: stand on a place in the grid (a click on a cell or on a column label, or the arrow keys; **Enter** on a cell goes to the box for the comment), choose **this cell, this row, this column or the whole template**, write the comment and add it to the request. Give the request a title and send it. The sheet cannot be changed from here and the request changes nothing in it.
2. **Who sees it.** The person who asked, the owner of the template and everybody who holds the Approver policy. No other reader, and no other author. If the person who asked can no longer read the template they no longer see their request.
3. **Review.** Under **Change requests** the owner sees each request with its comments, the version it read, which places are still in the version in use (**no longer in this version** for one that a later version removed), and can comment, **accept**, **decline** (with a reason the person reads), or choose requests and **turn them into a new version**: a draft that is a copy of the version in use, to which the requests are linked. If a draft already exists they are linked to it as it is. The draft is edited in the sheet and sent for approval like any other. A rejected or withdrawn version keeps its comments; the owner can **reopen** the request.
4. **Told by notice.** The owner when a request arrives or the requester comments; the requester when someone else comments or the request is accepted, declined or turned into a version. A person can switch these off (Change requests in their notification preferences). No mail is sent.
5. **Nothing to set up**: no new policy, ability or setting. Asking is the right to read the template; answering is the right to change it.

### What exists in this release (FA4): the lifecycle of a template

A template now goes through its whole life.

1. **Draft.** A person who holds the **Author** policy makes a template (name, category, sub-category; for a control plan, a process flow chart or a failure mode analysis also the **part code**; for a business plan the **plan year**), starts its first version and builds the sheet. Only the author (and the people who approve) can see it: **a template is private**, and no setting changes that.
2. **Submit.** The author presses **Submit for approval** on the template's page. The approval process is the one the organization's administrator built for Template in the approval setup, with each step naming its approvers (by role, group or person). A draft that is still empty, a template without a category, a number that cannot be made yet, or a number somebody else already has, is refused in plain words before anything starts.
3. **Approve, and the number.** Each approver presses **Approve** on their task. **The first approval gives the template its number** (for example `WI/PRD/01`), once; a template rejected at the first step takes none, and a number is never given again. The approver sees "Approving this gives it its number, WI/PRD/…" while the approval is theirs.
4. **Approving is not publishing.** An approved version waits. Only the people who approve and the author can see it.
5. **Publish.** A person who holds the **Approver** policy presses **Publish**, chooses a **Location** and then a **Department** (the departments narrow to the location), adds as many as needed, and publishes. The template becomes visible to **the people of those departments** (the group the administrator set up for each) in the Templates list, and to nobody else. Publishing again shows the audience it has and keeps it unless you change it.
6. **Unpublish, a new version, retire.** An approver can take a template back from the people (**Unpublish**). The author starts a **new version** from the one in use; while it is in review the version in use stays readable and in use, and approving the new one does not publish it: publishing it replaces the old one at once. **Retire** ends a template for good: it is kept, its number is never given again, and the people who had it still read it. It cannot be retired while a version waits for approval.
7. **The master list.** The Templates tab lists the templates you may see with number, name, category, sub-category and lifecycle ("Active v3, v4 in review"), with search, filters (category, sub-category, status, retired) and an order. **New template** is shown to people who may create one.

While a version is in review, **only the people who approve it can change it** (they correct the sheet or the details; every correction is recorded in the audit trail by size and fingerprint, never by content). The author who submitted it cannot.

### What the organization's administrator sets up (FA4)

The steps in order are in `docs/metadata-guide.md` section 29 (and the demo set-up in section 34). In short: give the policies **Author**, **Approver**, **Reader** and **Department setup** to the right people or groups (an access policy is never given to a role); build the approval process on Template; make one **public group** for each department and put its people in it; and add one **Department group** record for each Location and Department that names that group (Audit maintenance, Department groups). A department with no record, or with an empty group, cannot be published to and the dialog says so.

### What existed in FA3

Since FA3 a template has a **sheet**: open **Document Control** in the Quality Management System application, open a template (or choose **New template**), start its first version, build it in the Form view or the Grid view, save it and keep its versions. The full description for the administrator is [metadata-guide.md](../metadata-guide.md) section 27. In this release a version is only ever a **draft**: submitting for approval, the number, publishing and retiring come next (FA4A and FA4). The page lists the templates plainly (no filters, no lifecycle summary: the master list is FA4).

### What existed in FA2A

The framework (FA1) and, since FA2A, the first fields of the object and the numbering. The templates themselves, the grid, the approval and the publishing come in later sprints (FA3 to FA5).

**The object Template now has** a **Category**, a **Sub-category** (only the sub-categories that go with the category can be chosen) and a **Number** that nobody types. The lists of categories, sub-categories and which go together belong to the organization: the platform ships a starting list, the administrator changes it in the object manager (Object settings, Template, Fields, open the field, **Edit the values**), and a member who holds the ability **Add values to picklists** can add a new abbreviation from the form. The number is written in the format of the category (for example `WI/PRD/03`) and counts up for each category and sub-category, from 1; a newly added abbreviation starts at 1. The number is given by the platform when the template is approved, in a later sprint; in this release nothing gives a number yet. The starting list is a placeholder (the 16 categories of the product owner's scheme and a few generic sub-categories) and is replaced by the product owner's real list as a data-only change.

### What existed in FA1

Only the framework and its first object. An organization that has the feature sees an application called **Quality Management System** in the application launcher, with one page, **Templates**, that says "nothing here yet". The object **Template** (`InspectionTemplate`, a name and a "retired" flag) exists and can be read through the usual record screens and API by a member who is allowed. The templates themselves, the grid, the approval, the numbers and the publishing come in later sprints (FA2A to FA5).

### How to give an organization the feature

1. Sign in on the platform address as a platform administrator and open the console.
2. Either add the feature **Quality Management System** to a plan (Plans, open the plan, Change, tick the feature) or give one organization an override (Organizations, open it, Features, "Switch on"). There is no licence to buy and no quantity to set.
3. Taking the feature away shows the platform's own words first: the application and its objects are hidden at once, **nothing is deleted**, and everything comes back when the feature is given again.

### What the organization's administrator does

1. The five access policies **Author**, **Approver**, **Reader**, **Filler** and **Department setup** appear in Access policies on their own, marked "Comes with the platform", the first time anyone in the organization opens a page after the feature was gained. They can be given to members and groups; they cannot be changed or removed. They use no licence.
2. Until a person holds one of them they see nothing of the application. Since FA4 the policies hold the real rights (create and change a template, view every template, publish, retire, correct a version in review), described in section "What exists in this release (FA4)". Which people hold which is the administrator's decision: give **Author** to the people or groups that write templates and **Approver** to the people or groups that approve and publish. Since FA6A the Author edits the name, Level and Document; the Approver sets the identity category and sub-category (the numbering) while the template is in review, and may add a missing picklist value from the form.
3. The records of the Template object are **kept**: nobody can delete one (the delete right does not exist for this object), and a template that is no longer used is marked **Retired**.

### On a developer machine

The `local` profile switches the feature on for `tenant-a` and leaves `tenant-b` without it. Sign in at `http://tenant-a.localhost:3000` as `admin-a@example.test` and open the application launcher.

## 2. For contributors

| What | Where |
|------|-------|
| The bundle: the feature, the application, the entries, the policies | `platform-app/src/main/resources/solutions/inspection/solution.yml` |
| The shipped object (platform definition; read by the metadata module) | `platform-app/src/main/resources/solutions/inspection/objects/InspectionTemplate.yml` |
| The number series: the formats by category | `platform-app/src/main/resources/solutions/inspection/numbering.yml` ([metadata-guide.md](../metadata-guide.md) section 26.4) |
| The feature row | migration `V058__add_inspection_feature.sql` |
| The department mapping object (the group of a department of a location) | `platform-app/src/main/resources/solutions/inspection/objects/DepartmentGroup.yml` ([ADR-0190](../adr/0190-department-groups-the-object-group-and-the-mapping.md)) |
| Fill-ins: the start, the list, the hook of the approval engine, the header writer | `platform-app/src/main/java/app/platform/inspection/internal/` (`FillIns`, `FillInController`, `FillInViews`, `FillApprovalHooks`, `FillHeaders`, `Fills`); the header object is `platform-app/src/main/resources/solutions/inspection/objects/InspectionFill.yml`; the cells and the pin are generic: `platform-app/src/main/java/app/platform/sheets/` (`FillLifecycle`, `internal/SheetFills`, `FillValidator`, `FillStructure`, `SheetFillStore`) and migration `V069__create_sheet_fill.sql` ([ADR-0192](../adr/0192-fill-ins-the-header-record-the-cell-table-and-review-by-the-approval-engine.md)) |
| The cluster's module: the hook, publishing, retiring, the master list | `platform-app/src/main/java/app/platform/inspection/` ([ADR-0188](../adr/0188-the-template-lifecycle-the-cluster-module-publishing-and-the-master-list.md)); the table `inspection_publication` is migration `V066__create_inspection_publication.sql` |
| The change requests: the rules, who sees what, the tables, the endpoints | `platform-app/src/main/java/app/platform/inspection/internal/` (`ChangeRequests`, `ChangeRequestAccess`, `ChangeRequestStore`, `ChangeRequestController`, `ChangeRequestViews`, `ChangeText`, `ChangeAnchor`, `ChangeRequestStatus`, `ChangeRequestProperties`); the tables are `V067__create_inspection_change_requests.sql`; the notice kind and category are `V068__allow_change_request_notices.sql` ([ADR-0191](../adr/0191-change-requests-on-a-published-template.md)) |
| The page keys the web application knows | `platform-web/src/solutions/inspection/registry.ts` (the pages: `TemplatesPage.tsx` the master list, `TemplateDetail.tsx` one template with its lifecycle, `PublishDialog.tsx`, `lifecycle.ts`, `api.ts`; since FA5 `AnnotatePanel.tsx` the composer under the grid, `ChangeRequestsSection.tsx` the list and the review, `changeRequests.ts` the calls and the words) |
| The sheet: versions, validation, the grid | generic: module `sheets`, `platform-web/src/lib/sheets`, `platform-web/src/components/sheets` (no cluster word); the bundle names the owner in `sheetOwners` of `solution.yml` |
| The generic code | modules `solution`, `metadata`, `data`, `security`, `application` (no cluster word; `SolutionBoundaryTest`) |

**Add a page.** Add the page component under `platform-web/src/solutions/inspection/`, register its key in `registry.ts`, and add a `PLATFORM` entry with that `pageKey` (and the object whose read right shows it) to `solution.yml`. The frontend test compares the two lists.

**Add a field or an object.** Edit the object file; never delete or rename a released field (set `retired: true`); run the three commands of [metadata-guide.md](../metadata-guide.md) section 4. A new object also goes into the `objects` list of `solution.yml` with the same `requiresFeature`.

**Change a policy.** Edit it in `solution.yml`. Every organization that has the feature gets the new rights at its next page load (the stored fingerprint differs). Never give the delete right on an object whose records are kept; the loader refuses it.

**Rules to keep.** The cluster's words (`inspection`, "template", "audit maintenance") appear only in the places `SolutionBoundaryTest` lists; the generic modules say `sheet`, `version`, `fill`. Nothing in the bundle can be edited or removed by an organization. A page asks the API for everything: the browser decides nothing.

## Fill-ins, the rest (FF2)

| What | Where |
|------|-------|
| The report and the file rule | `platform-app/src/main/java/app/platform/inspection/internal/` (`FillReport`, `FillFileRules`, the endpoints in `FillInController`) |
| Report values, archive | `platform-app/src/main/java/app/platform/sheets/` (`FillReports`; `internal/FillValues`, `FillValueStore`, `DefaultFillReports`, `FillArchive`, `FillArchiveJob`) |
| The report page | `platform-web/src/solutions/inspection/` (`FillInReportPage`, `report.ts`, the page key `fill-in-report`) |

The application has a "Report" entry beside "Fill-ins". A fill-in page has an "Attachments" section and shows "Archived" for a fill-in whose cells are in the archive. Decision: ADR-0193.

## The lists and the template page (UI3)

Frontend only; the decisions are in the UI3 entry of `docs/delivery-plan.md`.

- **A list** of this application is a `ListPage` (`components/ui/lists`): one search box over the columns it names (`searchText`), a click on a header orders by it (`sort` on a column, `sortPrimary` for the first; high to low, then low to high, then as read), New to the left of the search box, and the settings icon (`menu`) only when it holds something. There is no filter pane and no sentence under the title. Search and ordering work on the rows read so far.
- **The template page** (`TemplateDetail.tsx`) has three tabs in the address (`?open=<id>&tab=sheet|fill-ins`; none means Details). Details are read first and edited in place (`RecordForm` with `formId` and `hideActions`; Save and Cancel are in the title band). The buttons in the band follow the flags of the server: Submit for approval, Approve and Reject (`useRecordApproval`, `ApprovalHeaderActions` in `components/records/ApprovalPanel.tsx`), Publish; Unpublish, Retire and the rare approval actions are in the "more" menu (`ActionMenu`). After an approval ends and the lifecycle says `canPublish`, the Publish popup opens by itself (two acts of the server, one flow).
- **A new template** is a popup (`Modal`) over the list (`?new=1`); a save opens the new template.
- **The Sheet tab** lists the versions first (`SheetWorkspace` with `listFirst`) and opens one with its grid, a way back to the list inside the tab, and the change requests under it. The Fill-ins tab is `FillInsList` with `embedded`.
- **No back link.** The strip's tab "Templates" leads back; pages of this application draw no breadcrumb (`DetailPage noBreadcrumb`) and the record screens draw no "back to the list" link. Setup and console pages keep theirs.
- **Field descriptions** are not drawn in forms; they stay in the setup screens.
- **The sheet** starts with ten columns and, when the sheet names its rows, ten rows (`BlankGrids`, backend; a copy of a version is never blank). A column is widened by dragging the right edge of its label in the author's grid (`SheetGrid` prop `onResizeColumn`, given by `GridView` only when the grid can be changed and is not in annotate mode; the grid of a filler gets none, the width belongs to the sheet); the width is part of the sheet format (40 to 1000 pixels) and one drag is one undo step.

## The list views, the settings menu and the server-side search (UI3A)

The decision is ADR-0194. One API addition (two `POST` queries), no migration.

- **The title row of a list** is `view name ▾ | New | search | settings icon`, then the table. `ListPage` takes `titleControl` (the view menu, standing where the title was; the title still names the page for assistive technology) and `remote` (the search text and the order are the screen's, the rows come back already searched and ordered). A list that gives neither keeps the old client-side behaviour.
- **`useListViews`** (`components/ui/lists`) is what a screen uses: the object's saved views (`/objects/{object}/views`), the field description of the object (`useObjectForm`), the person's choice of columns, the filter, the order and the search text as one `ListRequest`, the view menu, and the popups behind the settings menu (`FieldPicker`, `FilterBuilder` in a `Modal`, `SaveViewPanel` for saving and `Remove this view`). It decides nothing: fields and comparisons are the server's list (`filterOperators`, `sortable`), and the server answers again every question.
- **A new list of this application** passes `objectApiName`, the fields its rows carry (`displayable`), the fields shown before a view is chosen (`defaults`), the field of the first column (`primaryField`) and the name of the list with no view (`allLabel`); it asks the server with `views.request` and starts again when `views.requestKey` changes. A column that is a field has its `key` equal to the field API name and a `sort`; a column that comes from somewhere else (the versions, the fill-in row) has neither, so its header is not a button.
- **The server side:** `POST /api/v1/templates/query` (`TemplateList.query`) and `POST /api/v1/fill-ins/query` (`FillIns.query`) take `search`, `filter`, `sort` and the cursor (a template also a `state`, a fill-in a `templateId` and `status`). They go through `Records.query`: record security, field security and the scan limit apply as for any list. `QueryFilterNodes` (the root of `data`) turns the API's filter into the store's. A typed text is never logged (`TemplateQueryIT`).
- **Delete is not drawn** (kept records); Retire is on the template page. The count of fill-ins on a template and the unsaved-changes popup were left out (ADR-0194).
- **Remembered view:** the last open view of each list is kept in the browser (`list-view:<object>`), per browser, never on the server.
- **Tests:** `TemplateQueryIT` (differential against the plain list, the cursor, who may reach what, refusals, the leak test); frontend `TemplateListViews.test.tsx` (the title row, views, the two boxes, the filter, the search and the order asked of the server), `TemplatesPage.test.tsx`, `FillInsPage.test.tsx`, `noIntro.test.ts`; the kept browser script section 12.

## Upload of a spreadsheet, "Last changed by" and the height of a row (UI3B)

The decision is ADR-0195. One API addition (`POST .../versions/import`), no migration, no module edge.

- **For an author:** on the Sheet tab, **Upload a spreadsheet** (beside the new-version button, when no draft exists) and, on a template with no version yet, **Start from a spreadsheet** (beside **Start the first version**). Choose an .xlsx or .csv file: a window says what the file gives ("12 columns, 80 rows, 3 merged areas"), what was left out and what came in another form, and has a box for whether the first row names the columns. Nothing is saved until **Save as a new draft**. Text in the file becomes fixed text of the sheet; a cell with no text is left for the filler; widths, heights, bold, italic, the four sizes and merged areas come with it; formulas come as their last values and dates as year-month-day. A draft that exists refuses the upload and names its number. The file is kept nowhere.
- **The reader** is `app.platform.sheets.internal`: `SpreadsheetImporter` (formats, the places that bound the files read at once, then the builder and the validator), `XlsxReader` (the JDK's zip and XML readers, no library), `SheetCsvReader`, `SheetImportBuilder` (rows, columns, merges, notes, and the checks by position), `RawSheet` (what a reader found; no library type), `ImportNote` (the words for what was left out), `ImportRefusal` (a code and fixed words), `SheetImports` (the part that asks the rules of the sheet: `plan` before the body is read, `start` under the sheet's lock), `ImportProperties`. No business word is in any of them (`SolutionBoundaryTest`).
- **A new refusal** needs a line in `src/test/resources/sheets/hostile-spreadsheets.txt` and a generator in `SpreadsheetCorpusTest`; the test fails when a code has no line. A new thing the reader leaves out needs an `ImportNote` with its words.
- **Settings:** `platform.sheets.imports.*` (`max-file-bytes` 5,000,000, `max-part-bytes` 20,000,000, `max-inflated-bytes` 100,000,000, `max-entries` 100, `max-cells` 200,000, `max-concurrent` 4). **Deployment requirement:** the proxy sets a request size limit of at least `max-file-bytes` for the path.
- **Frontend:** `components/sheets/UploadSheet.tsx` (the button, the window, preview and save), `lib/sheets/upload.ts` (the kind of file, the size check that spares a long upload, the words), `importSpreadsheet` in `lib/sheets/api.ts` (one raw request, made the way every call is), the column "Last changed by" in `VersionList`, and `RowEdge` in `SheetGrid` (the handle on the bottom edge of a row, given only by the author's grid through `onResizeRow`). The browser decides nothing: the server reads the file again and says why it refuses.
- **Tests:** no database: `SpreadsheetImporterTest` (a good workbook and a good text file, every limit at the limit and one above, the entity attack, a property over any text file and any workbook), `SpreadsheetCorpusTest` (the hostile corpus). Real PostgreSQL: `SheetImportIT` (allowed, read only, no access, another organization, forged host, platform administrator, not signed in, a draft exists, the first version of an empty sheet, the shape of a sheet that has versions, a limit, nothing stored on a refusal, a race of two uploads), `SheetImportLogsAreCleanIT`. Frontend: `UploadSheet.test.tsx`, `upload.test.ts`, `SheetWorkspace.test.tsx`, `GridView.test.tsx` (the row-height drag); the kept browser script section 13.

## Change the tabs of the application

The application is in `platform-app/src/main/resources/solutions/inspection/solution.yml` (`application.entries`), computed on every request and stored nowhere for an organization, so the setup Applications screen does not list it and an organization cannot change it. To **add** a tab: write the page under `platform-web/src/solutions/inspection/`, register its `pageKey` in `registry.ts`, add a `PLATFORM` entry with that key (and `readsObject` when a read right should show it). To **rename** one: change its `label` (the key stays). To **reorder**: move the entry. To **remove**: delete the entry and its registry line (the frontend test compares the two lists). A change reaches every organization with the feature at the next release. A tab for one organization only is not possible today.

## Level and Document, the identity pair, the department of a request and Submit (FA6A)

The decision is in [ADR-0196](../adr/0196-the-identity-pair-at-the-first-approval-level-and-document-the-department-of-a-request-and-submit-without-a-process.md), the administrator's view in [../metadata-guide.md](../metadata-guide.md) section 34.

- **Fields:** `InspectionTemplate.level` (controlling) and `documentType` (dependent), organization-owned lists with a short placeholder start, no number. The numbering pair keeps its API names `category` and `subCategory`; on screen they are "Identity category" and "Identity sub-category".
- **Policies:** Author edits `name`, `level`, `documentType`; Approver edits all, and holds `field-values.add` besides `approvals.override-lock` (publish and retire belong to Publisher, FA6E). The read lists of every policy include the new fields (a field a policy does not list does not exist for the person).
- **Hook:** the numbering checks are in `TemplateApprovalHooks.beforeApprove`, asked only while the template has no number; `onSubmit` keeps the retired check and the sheet's own.
- **Change requests:** `requester_department` (V072), written once by `ChangeRequests.create` from `DepartmentAudiences.departmentsOf`; shown as `requesterDepartment`.
- **Fill-ins:** `POST /api/v1/fill-ins/{id}/submit` (`REVIEW_NEEDED` or `SUBMITTED`); `FillSubmission`, `ApprovalApplicability`; the screen's Submit and Cancel are in `FillInDetail` and `FillWorkspace`.
- **Tests:** no database: `TemplateApprovalHooksTest`, `SolutionLoaderTest`, `StandardMetadataLoaderTest`; real PostgreSQL: `TemplateIdentityIT`, `FillInSubmitIT`, `ChangeRequestDepartmentIT`, `ChangeRequestFlowsLogsAreCleanIT`, `ShippedPoliciesIT`, `TemplateNumberingIT`, `TemplateLifecycleIT`. Frontend: `PublishDialog`, `FillInsPage`, `ChangeRequestsSection`, `TemplatesPage` tests.

## Numbering in Setup (FA6B)

The administrator opens **Setup -> Numbering** with `numbering.manage`, chooses a category or default, previews and saves a format for this organization. Formats are settings, not a code release. The precedence is the organization's category format, the shipped category format, the organization's applicable default and the shipped applicable default. The two defaults cover a category with or without its optional sub-category and start without zero padding. Existing category-specific formats, including the four typed-part formats without a count, are preserved.

FA6C moves normal allocation to the permitted identity Save; the first-approval hook remains a backstop for an unnumbered template. Preview observes but never consumes or reserves the next number. A saved format affects future allocations. The same fields continue the counter even if reordered; a different field set counts separately. An administrator may explicitly raise the next count of a category's current scope, never lower it. Fixed numbers are not rewritten. Fill-in and Report screens show the stored number where ordinary record and field permissions allow it; they never reconstruct it from today's format.

Implementation: `data/internal/NumberingController`, `NumberingManagement`, `NumberingFormats`, `NumberingStore`; V073 `numbering_format`; the shared-kernel series defaults and `NumberingLoader`; `NumberingPanel.tsx`; `TemplateNumbers` for a bounded read on fill-in/report pages. No new module edge. Decision: [ADR-0197](../adr/0197-organization-number-formats-previews-and-counter-continuity.md). Verification and pending checks are recorded in `Sprint FA6B.md`.

## Rehearsal fixes (FA6C)

[ADR-0198](../adr/0198-save-time-template-numbers-and-compatible-fill-formatting.md) amends allocation at first approval.
`TemplateIdentityWrites` contributes the number to the ordinary record pipeline. A permitted Save with complete inputs
returns its number at once. Before first Approve, changing numbering inputs allocates again; unchanged inputs retain it.
`NumberAssignments` retains earlier spellings in V074, even after rejection. Switching A -> B -> A uses A's next count.
First Approve fixes the identity and number; later rejection cannot undo it. Legacy issued numbers remain fixed.
`ObjectForms` describes fixed identity fields as read-only. Every write still enforces the rule on the server.

QSP's omission of a newly selected sub-category was reproduced through the normal API against a fresh jar. The saved
record retained the selected code. Setup reported a shipped format that ignored it; changing the picklist never changes
that format. Shipped formats are retained. The administrator guide lists the current category groups and the override
needed for separate pair counts. Add value already selects the normalized returned code and persists `controllingValues`.

The new-sheet screen defaults to dimension 2 and has two centred start buttons. Dimension controls the starting template
shape; the published rows mode independently controls filler-added rows. Only the template is currently a shipped sheet
owner; backend dimension-1 support remains.

Fill-in v2 adds sparse per-input-cell styles, with old v1 bytes and hashes preserved. Reports still project only values;
archives retain the full canonical document. Bold, Italic, Underline, four sizes, Left/Centre/Right alignment,
Default/Accent/Green/Red text colour, reset and undo/redo work on editable inputs and template fixed text.
Structural tools stay visible and disabled. Draft/returned autosave uses a two-second debounce and one versioned queue;
refusals keep data and stop retries, and conflicts offer reload/reapply. The existing no-process Submit path is unchanged;
the acceptance check remains `FillInSubmitIT#withNoProcessSubmitChecksTheCellsThenEndsTheFillInAsSubmittedAndReadOnly`.

The bell opens a searchable/orderable shared table. Opening a notice uses its owned facts and normal record access,
then opens the canonical Templates tab; other targets remain on the list with an explanation. Success messages share
three-second expiry; errors persist. The sprint/manual documents record verification and the required jar restart.

## The popup, the approval flow, the product colour and the sign-in halves (FA6D)

Frontend only: no migration, no endpoint, no change to who may ask for a change or to the approval engine.

**Ask for a change is a popup.** `AskForChange` draws a button in the sheet toolbar (the row of the tabs) while the version in
use is open and the API says the person may ask (`canRequest`). The shared `Modal` covers the page, so the person stands on a
place in the grid first and the popup names it (cell, row, column or the whole template); Enter on a cell opens it too.
`AnnotatePanel` (since FA10 `RequestPanel`) is only the content of the popup. What was written lives in `useAnnotateDraft` (since FA10 `useRequestDraft`), held by `AskForChange`, so
closing the popup never drops it: with something unsent, a close (the x, Escape) asks "Keep writing / Close and keep my words /
Discard my words". A send closes the popup and shows a three-second notice; a refusal stays inside the popup in the API's words.
`ChangeRequestsSection` is unchanged.

**The approval flow** is drawn beside the details on the Details tab (below them on a screen narrower than 56 rem) and only when
the template has had a request. `lib/approvals/flow.ts` (pure) turns the API's `RecordApprovalView.current` into lines: the person
who submitted, then one line per step with its state and one short line (who decided; "Waiting for N people"). A step that needs any
one shows the person who decided it. The dot is green on the step where the request stands (the first step not finished; the last
for an approved request; the last reached one for any other ending), red on a rejected step, grey for a step not reached or skipped.
`ApprovalFlow` is an `ol` with the state in text and `aria-current="step"`. The card keeps the decisions and the history
(`ApprovalCard steps={false}` leaves out its own list on this tab); the "History" link opens the card's history. The API carries no
group for the person who submitted, so the first line says "Submitted"; it sends only the latest request.

**The accent** is `#93418b` (hover `#7a3374`, soft `#f6eef8`; dark mode `#d68fcf`, hover `#e8b0e2`, soft `#2b1a2c`, text on the
accent `#1c0f1b`). Only `styles/tokens.css` holds colours; `tokens.test.ts` keeps the old green out and checks WCAG AA (4.5 to 1)
for every pair of accent and background in both themes. The success and danger colours are unchanged: an approved tag and the
flow's green dot are status colours, not the brand.

**The sign-in screens** (the welcome page for a signed-out visitor and `/sign-in`, on the platform's address and an
organization's alike) share `SignInLayout`: the form centred in the left half, the product's mark (`public/logo.png`, drawn at its
own size) centred on `--color-brand-panel` in the right half; under 48 rem the mark goes above the form. The product's name stays
`lib/brand.ts`; the alt text is generic.

Tests: `AskForChange.test.tsx`, `AnnotatePanel.test.tsx`, `flow.test.ts`, `ApprovalFlow.test.tsx`, `SignInLayout.test.tsx`,
`tokens.test.ts`, and the FA6D cases in `TemplatesPage.test.tsx`.

## Change requests through an approval, the tab, the asterisk and who publishes (FA6E)

**Partly replaced by FA8 (last section): there is no accept, turn or reopen, and the asterisk is on the tab.**

Decisions in ADR-0199. One migration (V075), one new object, one new ability, one new entry; no new table.

**A request has a review of its own.** Raising a request also makes a small private record of the object **Change request**
(`InspectionChangeRequest`, kept for good, three fields only the platform writes) and starts the organization's process for that
object, as the person who asked. The request itself stays in its two tables (`header_id` links it to the record). When the process
ends approved the request is **accepted**; rejected, it is **declined for good** (the person raises a new one); withdrawn, it
stays open. Only an accepted request can be turned into a version. The process is data made in Setup, Approval processes, on the
object **Change request**: Plant QMS (step 1), Corporate QMS (step 2, "skip when nobody"). **The groups are shared with the
template process; the steps are edited on each process separately.** An organization without such a process cannot raise a request: the person is told to ask
an administrator to make it (Setup, Approval processes). `scripts/demo-setup.mjs` makes the process "Change request review" in `tenant-a`.

**The tab.** The application has an entry **Change requests** beside Fill-ins (page key `change-requests`, reads the object
Change request, so it is listed for Author, Reader, Filler and Approver). `ChangeRequestsPage` lists the requests of every
template the person may see: the template (name, number, the title under it), who asked, department, version, date and status; one
search box (title or the name of the person who asked), a click on Version, Date or Status orders the whole result on the server,
"Show more" continues from a cursor. A row opens `ChangeRequestPage`: at the top left the template and number and the version
read, in one row who asked and the department, the first comment, the changes requested place by place, and at the top right
**Accept** and **Decline** (the buttons of the request's approval, shown to the people who hold a task in it; declining asks for a
reason). A reviewer sees **Turn into a new version** once it is accepted. The
section on the template page stays, with a link to the request page. The address is `/apps/inspection/s-change-requests?open=<id>`.

**The tab on the template.** The template page has a fourth tab, Change requests, after Fill-ins (`TemplateDetail`, `ChangeRequestsList` with `templateId`): the requests of that template through the same query, and the request page opens inside the tab (`?open=<template>&tab=change-requests&request=<id>`). The bell rows about a request (the `CHANGE_REQUEST` notice and the approval notices of the review record) lead to that address (`RequestRecords.pathOf`).

**The asterisk.** A template with at least one request that is **open or accepted** (and not yet made into a version) has a small
asterisk in the accent colour at the right of its row in the Templates list and at the right of its page's title band
(`OpenRequestsMark`, role `img`, "Has open change requests"). The number is `openChangeRequests` on the list row and on the
lifecycle view, counted as far as the person may see the requests.

**Who publishes.** The abilities `sheets.publish` (publish and take back) and `sheets.retire` belong to the shipped policy
**Publisher**, not to the Approver. An administrator gives Publisher to the group Corporate QMS, and to Plant QMS in a flow with no
Corporate step (and takes it away again). The lifecycle view's `canPublish` and the acts ask the ability, so a hidden button is not the
rule; nothing reads the approval's record. The Organization administrator holds every ability and always publishes.

**The history on the Details tab.** `TemplateApprovals` replaces the single flow with an accordion by date (`ApprovalHistoryList`,
each entry the same `ApprovalFlowView` as the card): "New template" (the approval of version 1), "New version N", and "Change
requested" for each change request that went through an approval, newest first, the newest open. It calls
`POST /api/v1/objects/{object}/records/approval-history` twice (the template, and the review records of its newest change requests).

**Told by the bell.** The engine's task notices tell Plant QMS when a request is raised and Corporate QMS when the first step
accepts; the `CHANGE_REQUEST` notice (now with the fact `request`) tells the owner of the template and the person who asked of the
answer; a bell row opens the request page. No mail.

**Tests.** No database: `ApprovalHistoryList.test.tsx`, `OpenRequestsMark.test.tsx`, `ChangeRequestsPage.test.tsx`,
`ChangeRequestPage.test.tsx`. Real PostgreSQL: `ChangeRequestReviewIT`, `ChangeRequestListIT`, `TemplateOpenRequestsIT`,
`TemplatePublishAuthorityIT`, `ApprovalHistoryIT`, `ChangeRequestReviewLogsAreCleanIT`, and the updated `ShippedPoliciesIT`,
`RetainedObjectsIT`, `SolutionLoaderTest`, `StandardMetadataLoaderTest`, `ChangeRequestsIT`.

## The suggestion made at approval (FA6G)

The decision is ADR-0200. A step of the template's approval process can be marked **asks for a choice**; the approver of that step sends
the places the template is meant for (a location, required, and departments, optional) together with the approval, and the cluster keeps
them as a suggestion for the person who publishes.

- **Where it lives.** `AudienceChoices` (the pure rule), `AudienceSuggestions` (judges against the lists the organization offers now, writes
  once, audits by count), `AudienceSuggestionStore` (the only class that names the table `inspection_audience_suggestion`, V076, written only
  under the transaction setting the store raises, never changed or deleted), `TemplateApprovalHooks.beforeApprove` (asks for the choice at a
  marked step), `DepartmentAudiences.offered` (the lists alone, no mapping records), `TemplatePublishing.suggestionOptions` and the endpoint
  `GET /api/v1/templates/{id}/suggestion-options` (any caller who can see the template; the lists are the organization's words), and
  `TemplateLifecycleView.suggestedAudience` / `suggestionDropped` (only for a caller who may publish, for the version Publish would make
  current, read from the request that reviewed it).
- **Screens.** `solutions/choices.ts` registers `AudienceChoiceForm` for the template object; the generic `ApprovalActionForm` shows it at a
  marked step and sends the choice; `PublishDialog` starts from `suggestedAudience`.
- **Never** log, audit, publish in an event or put in a notice a location or department. The audit record is
  `inspection.audience.suggested` with the number of places.
- **Tests.** No database: `AudienceChoicesTest`, `ApprovalProcessCodecTest`, `ApprovalProcessRulesTest`. Real PostgreSQL:
  `AudienceSuggestionIT` (the flows, the refusals, who sees what, another organization, the database guard, the clean trail); the isolation
  harness lists the table. Frontend: `ApprovalActionFormChoice.test.tsx`, `PublishDialog.test.tsx`, `choices.test.ts`,
  `approvalModel.test.ts`, `ApprovalBuilder.test.tsx`.

## View, Download, Sheet options and the footer row (FA7)

Short form; the decision is [ADR-0201](../adr/0201-view-and-download-of-a-template-sheet-view-only-the-footer-row-and-revisions.md), the
administrator's view is [metadata-guide.md](../metadata-guide.md) section 39.

- **The options** are the table `inspection_template_option` (V079): `view_only` and `allow_download`, one live row for a template that had
  them set, none for a template that did not (both off). The database lets a row in or change it only when `platform.template_option_write`
  is on, which only `TemplateOptionStore.set` raises in its own statement; no delete, no move to another template. It is a table and not
  two fields of the header because the header is locked during an approval. `TemplatePublishing.publish` saves them (a value left out
  keeps what the template has) and `setOptions` (`PUT /api/v1/templates/{id}/options`) changes them; both need `sheets.publish`.
  `TemplateLifecycleView` carries `number`, `viewOnly`, `allowDownload`, `canDownload` (the option, or the ability) and `canSetOptions`.
- **View only** is judged in `FillIns.why` (so `fill-in-options` and `start` agree): the words are `FillIns.VIEW_ONLY`, the metric result is
  `view_only`. A fill-in that exists is not touched.
- **Download** is `TemplateDownloads` (visible, then allowed, then the revision) behind `GET /api/v1/templates/{id}/download`; the file comes
  from `SheetExports.xlsx` (module `sheets`, cluster only, an architecture test names it) which asks `SheetVersions.readable`, the rule of
  the Sheet tab. The footer text and the file name are `TemplateSheetTexts` (pure). The audit record is `inspection.template.downloaded`
  (template identifier, revision number, bytes); never the number or the file name.
- **The file** is `XlsxWriter` in `sheets` (no library): inline strings only, the quote prefix on a text that starts with `=`, `+`, `-` or
  `@`, no formula element, six parts, a bound of 5,000,000 bytes, the input cells empty, landscape when the columns add up to more than
  720 pixels. A new rule about what the file holds is a case in `XlsxWriterTest`, which also reads the file back with the reader of ADR-0195.
- **The footer row** is not in the stored grid. `SheetGrid` takes a `footer` text (absent: no row; empty: a blank row) and draws one more row;
  `SheetWorkspace` takes `footer` (a text, or a function of the revision that is open) and `actions` (extra buttons beside View);
  `TemplateDetail` builds the text with `footerText(number, activeRevision)` from `lib/sheets/revision.ts`, the same rule as
  `TemplateSheetTexts.footer` on the server (the same cases are tested on both).
- **View** is `components/sheets/SheetPrintView.tsx` over `lib/sheets/print.ts` (a pure model: head rows repeat, merged cells, percentages
  of the page width, the orientation by `PORTRAIT_WIDTH`). Style it with the `sheet-print-*` classes and the `--color-print-*` and
  `--sheet-page-*` tokens; the page is white in both themes. It is in the generic sheets components and knows no business word.
- **Revision**: `Revisions` (server) and `revision.ts` (browser) are the only places that turn a stored number into what people read. Do not
  write `version` followed by a number in any sentence a person reads.
- **Tests.** No database: `XlsxWriterTest`, `TemplateSheetTextsTest`, `InspectionArchitectureTest`, `SolutionBoundaryTest`. Real PostgreSQL:
  `TemplateSheetOptionsIT` (the options, who may change them, view only, download allowed and refused, another organization, the footer, the
  clean trail); the isolation harness lists the table. Frontend: `revision.test.ts`, `print.test.ts`, `SheetPrintView.test.tsx`,
  `SheetGridFooter.test.tsx`, `SheetWorkspaceView.test.tsx`, `SheetOptionsDialog.test.tsx`, `PublishDialog.test.tsx`.

## A change request is one controlled change (FA8)

Short form; the decision is [ADR-0202](../adr/0202-a-change-request-is-one-controlled-change-register-revised-copy-and-gapless-revisions.md),
the administrator's view is [metadata-guide.md](../metadata-guide.md) section 40. One migration (V080).

- **The register** is the table `inspection_change_register` (V080), one row for each request, made by `RegisterStore` only (the write
  settings `platform.change_register_write` and `platform.change_register_result` are raised in the statement itself). The pure rules are
  `RegisterRules` (what the requester types, what the approvers complete; the words name the box, never the text) and `RegisterLayout`
  (the page of the register, 2 columns and 14 rows, merged boxes); `RegisterValues` is the record of the values, `RegisterFacts` the facts
  the platform writes at raising, `RegisterViews` the endpoint records, `ChangeRequestRegisters` the acts (draft view, file out, file in,
  complete, stamp). The page is drawn by the generic `SheetPages` of the module `sheets` (page of cells to grid, to `.xlsx` and back).
- **The request** is `ChangeRequests.create` (the register is part of the body: `CreateChangeRequest`; the title is the first line of
  what is asked; one change in progress, checked under the template's change lock; the process must exist and its last step must not be
  skippable). `createCopy` makes the revised copy; `getByReview` finds a request from its review record. `ChangeRequestTasks` answers
  "does this person hold an open task of this request" through the shared-kernel contract `ApprovalTasks` and the new
  `ApprovalDecisions` (pending request, approvals with their days).
- **The revised copy** is a held draft: `SheetLifecycle.startHeldDraft`, `heldDraft`, `closeHeldDraft`, `retargetHeldDraft`. `SheetOwners`
  has `reachForDraft` and `mayReadHeld`; `SheetVersions` lists the Revision in use to readers, the history only to those who change,
  publish or override the lock, and saves a held draft for the people asked to decide (`canChangeHeld`). `SheetImports` takes
  `replace=true` to replace the grid of the held draft in place.
- **No draft by hand** for a published template: the shared-kernel seam `SheetDraftRules`, implemented by `TemplateDraftRules`.
- **The last approval publishes**: `ChangeRequestApprovalHooks.beforeApprove` when `DecisionContext.endsRequest` (needs `sheets.publish`,
  the held copy and a choice parsed by `AudienceChoices.parsePublish`); submit, reviewed as approved, `TemplatePublishing.publishRevised`
  (shares to the audience, keeps the options), `ChangeRequestRegisters.stamp`, request done. `onEnded` follows the record and closes the
  copy on a rejection or recall. `onSubmit` refuses a process whose last step may be skipped (`SubmitContext.lastStepSkippable`) and
  retargets the held draft. `ChangeRequestHeaders` stays the cluster's third use of `BackgroundWrites`.
- **Revisions**: `sheet_version.revision_no` is set once by the guard when a version first becomes the one in use; `TemplateRevisions` is
  the pure helper, the template fields `revision` and `lastRevisedAt` are platform-written (`TemplatePublishing.stampRevision`, skipped when
  the record is locked). `TemplateSheetTexts` and `TemplateDownloads` use the real Revision.
- **Endpoints**: `POST .../change-requests` (with the register), `POST .../{id}/revised-copy`, `GET`/`PUT .../{id}/register`,
  `GET .../{id}/register/file`, `POST .../change-requests/register/page|file|read` (the blank form drawn, taken as a file, read from a file),
  `GET /api/v1/change-requests/by-review/{reviewId}`. Gone: accept, decline (the approval declines), turn, reopen.
- **Frontend**: `register.ts` (calls), `RegisterForm` (the raise form and the form of a request), `RegisterExcelView` (a read-only
  drawing over the generic `GridView`), `RegisterPanel` (the toggle, download and upload), `AnnotatePanel` (the popup: register first, then
  the optional places), `ChangeRequestPage` (the register, the link "Current revision", Create revised copy, the revised sheet in the
  generic `SheetWorkspace` with `allowNewDraft={false}` and `focusVersion`), `PublishChoiceForm` and `ChangeRequestChoiceForm` (the window
  of the last approval; `choices.ts` has `choiceNeeded`), `OpenRequestsMark` on the tab of `TemplateDetail`.
- **Tests.** No database: `RegisterLayoutTest`, `RegisterRulesTest`, `AudienceChoicesPublishTest`, `ChangeRequestApprovalHooksTest`,
  `ChangeRequestStatusTest`, `DefaultSheetPagesTest`, `TemplateSheetTextsTest`, `InspectionArchitectureTest`. Real PostgreSQL:
  `ChangeRegisterGuardIT`, `SheetRevisionNumberIT`, `ChangeRequestReviewIT` (the whole chain, rejection at each step, gapless numbers,
  who may make and change the copy, the register), `ChangeRequestsIT`, `ChangeRequestsAccessIT`, `ChangeRequestListIT`,
  `ChangeRequestRacesIT`, `ChangeRequestFlowsLogsAreCleanIT` (the register values never reach a log, the audit, an event or a notice),
  `ChangeRequestReviewLogsAreCleanIT`, `TemplateOpenRequestsIT`, `FillInsIT` (a fill-in on an older Revision). A test that needs a second
  Revision in use calls `NextRevision.inUse` (a change request, the copy, the last approval); a test cannot start a draft of a published
  template by hand. The isolation harness lists `inspection_change_register`. Frontend: `register.test.ts`, `RegisterForm.test.tsx`,
  `RegisterPanel.test.tsx`, `PublishChoiceForm.test.tsx`, `ChangeRequestPage.test.tsx`, `ChangeRequestsSection.test.tsx`,
  `AnnotatePanel.test.tsx`, `AskForChange.test.tsx`, `ChangeRequestsPage.test.tsx`, `TemplatesPage.test.tsx`, `revision.test.ts`,
  `choices.test.ts`, `ApprovalActionFormChoice.test.tsx`, `SheetWorkspace.test.tsx`.

## Change request clean-up (FA10)

Short form; the decision is [ADR-0204](../adr/0204-change-request-clean-up-one-submit-rule-the-held-copy-on-the-sheet-tab-and-a-print-view.md).
No migration. This section replaces what the FA8 section says about the popup's places, the link "Current revision" and the revised
sheet drawn inside the request page.

- **Places are gone from the screens, not from the API.** The popup `AskForChange` holds `RequestPanel` (the register and Send; the unsent
  words live in `useRequestDraft`, outside the popup, so closing it does not throw them away) and sends `comments: []`. `AnnotatePanel`,
  `ChangeRequestsSection`, `lib/sheets/annotate.ts` and the grid's annotate mode (`annotation` on `SheetGrid`/`GridView`, `annotate` on
  `SheetWorkspace`) were removed with their tests and styles. `AskForChange` is a plain toolbar button on the Sheet tab, drawn when the API
  says the person may ask (`canRequest` or `inProgress`). The server still accepts places on a new request and old requests keep theirs.
- **The request page** (`ChangeRequestPage`) has one row of two buttons: **View Current Version** (a link to the Sheet tab) and
  **Create Revised Version** (`canCreateCopy`: makes the copy, then goes to `&tab=sheet&version=<copy>`), which reads **Open Revised
  Version** (a link to the same place) once the copy exists and `canChangeCopy`; nothing else is drawn for anyone else. The sentences for a
  published or declined request stay under the row. The sections "Where on the sheet" and "Revised sheet" are gone.
- **The Sheet tab** reads `&version=<stored number>`: `SheetWorkspace` opens that version at once (`focusVersion`, also with `listFirst`).
  The Download button is drawn when `canDownload` or when the open version is the held copy of a request the person may change
  (`canChangeCopy`); the server decides again (`TemplateDownloads`, then `SheetExports`/`SheetOwners.mayReadHeld`).
- **Submit** is greyed out when the lifecycle view says `canSubmit: false` (`TemplateDetail` passes `submitDisabled` to
  `ApprovalHeaderActions`; the title is `submitNote`). The rule is `TemplateSendRule`, used by `TemplatePublishing.view` and by
  `TemplateApprovalHooks.onSubmit`. The fill-in page and the change request page draw no Submit.
- **View of the register** (`RegisterPrintView`, opened by the *View* button of `RegisterRequestPanel`): the shared `Modal` (new `wide`
  option), `printModel(doc, { labels: false })` for the cells, and the server's `RegisterView.print` (`SheetPages.PrintSettings`,
  produced by `XlsxWriter.printSettings`, the method the file writer reads) for the size of the paper, the orientation and the margins.
  The raise popup has no View button.
- **The asterisk** (`OpenRequestsMark`, `.open-requests-mark`) is inline after the word of the tab.
- **Tests.** No database: `TemplateSendRuleTest`, `TemplateApprovalHooksTest` (the refusal words equal the rule's), `OpenRequestsMark.test.tsx`
  (the mark is in its tab and the style is not positioned outside it), `RequestPanel.test.tsx`, `AskForChange.test.tsx` (no place part),
  `RegisterPrintView.test.tsx`, `ChangeRequestPage.test.tsx` (the two buttons), `TemplatesPage.test.tsx` (Submit, the `version` address and
  the download), `print.test.ts`. Real PostgreSQL: `TemplateSubmitRuleIT` (every state, the field agrees with the send),
  `HeldCopyDownloadIT` (a task holder and the administrator allowed; a reader, another organization and a caller who is not signed in
  refused; the version by number). Removed with the features: `SheetGridAnnotate.test.tsx`, `SheetWorkspaceAnnotate.test.tsx`,
  `annotate.test.ts`, `AnnotatePanel.test.tsx`, `ChangeRequestsSection.test.tsx` and the places and "Revised sheet" cases of
  `ChangeRequestPage.test.tsx` and `changeRequests.test.ts`.
## The Quality Management System home page, the rename and the grid (FA9)

Decision: [ADR-0203](../adr/0203-the-application-grid-home-in-every-application-placeholders-and-the-quality-dashboard.md). Administrator's view:
`docs/metadata-guide.md` section 41. The process applications are on [their own page](processes.md).

- **The rename changed words only.** The application is **Quality Management System**; its tabs are Home, Document Control (was Templates),
  Change requests, Record Control (was Fill-ins), Report, Management Review and Internal Audit (the last two are announced and have no page
  yet) and, for a person who may read it, Department groups. The API name `inspection`, the feature key `inspection`, the page keys
  (`templates`, `fill-ins`, `change-requests`, `fill-in-report`) and every address are unchanged. The shipped access policies carry the new
  name in their descriptions; they are refreshed at the first menu request after the new jar.
- **Home is the dashboard** (page key `home`, `solutions/inspection/home/`): `QualityHomePage` (the screen and its address state),
  `FilterBar`, `StatTile`, `ChartCard`, `charts.tsx` (area, ring, bars, stacked bars, columns), `ActivityList`, `model.ts` (pure: periods,
  the change words, number formats, the geometry of the graphs), `api.ts`. Everything on it is one answer of
  `POST /api/v1/quality-home/summary` (`QualityHome`, `QualityHomeController`, `QualityHomeViews`, `QualityHomeStore`, `HomePeriod`,
  `QualityHomeProperties`), plus the header's own count of approval tasks.
- **Rules for a number.** It is counted over the records the record store lists for the person (templates, fill-ins) and over
  `ChangeRequestCounts` (change requests): it equals the list it opens. The record store gives no total, so the headers are read in pages
  up to `platform.inspection.home.max-scanned` (50,000); a number that hit it says "at least". A section whose object the person cannot read
  is absent. Nothing is cached across people. Days are UTC.
- **A new tile** is: a field in `TilesView`, its computation in `QualityHome`, an entry in `tilesOf` (`QualityHome.tsx`) saying what it
  opens and which filters it follows, a case in `QualityHomeIT` against the list it equals. **A new graph** is a field in `ChartsView`, a
  component in `charts.tsx` built from `ChartCard`, and a table of the same numbers.
- **Two small records** (V082): `inspection_fill_department` (written by `FillIns.start` from `DepartmentAudiences.placesOf`) and
  `inspection_template_event` (written by `TemplatePublishing` in publish, revised publish, unpublish and retire). Both are append-only.
- **Lists that a tile opens** accept the narrowing in the address: `/apps/inspection/s-templates?state=ACTIVE|IN_REVIEW|...` and
  `/apps/inspection/s-fill-ins?status=SUBMITTED|...`; a note over the list says it is narrowed and offers "Show everything".
- **Tests.** No database: `HomePeriodTest`, `SolutionLoaderTest`, `SolutionValidatorTest`, `SolutionBoundaryTest`; frontend `model.test.ts`,
  `QualityHome.test.tsx`, `ApplicationGrid.test.tsx`, `grid.test.ts`, `ApplicationOverview.test.tsx`, `Landing.test.tsx`,
  `ControlBar.test.tsx`, `AppLauncher.test.tsx`, `tokens.test.ts`. Real PostgreSQL: `QualityHomeIT` (the numbers equal the lists, no
  section for what cannot be read, filters, every period, cross-tenant, forged host, platform address, not signed in, feature missing, the two
  records), `QualityHomeBoundIT`, `QualityHomeLogsAreCleanIT`, `QualityHomeCostIT`; the isolation harness lists both new tables.

## The template Details tab and the approval history by day (FA11A)

Decision: ADR-0205. Frontend, one object file and documents; no migration, no API change.

- **Names a person reads** (labels only; stored names, the API and the policies are unchanged): Document name (`name`), Document type
  (`documentType`), Document number (`number`), Obsolete (`retired`), Revision number (`revision`). They are in `InspectionTemplate.yml`
  and in the generated list `docs/standard-objects.md`.
- **Two fixed columns** (`solutions/inspection/templateDetails.ts`, drawn by `RecordDetails` and `RecordForm` through the optional
  `left` of a section): left Document name, Level, Document type, Identity category, Identity sub-category, then Part code or Plan year;
  right Document number, Revision number, Obsolete (last). Reading shows Part code and Plan year only when the template has a value and
  the Revision number only once a Revision is in use; editing shows every field the member can see. Revision number and the date are
  never typed. **System information** holds "Last revised on" and the system fields. The generic record page is unchanged.
- **Revision number:** the saved value, else the Revision in use read from the sheet (the Sheet tab's rule). **Last revised on** is stored
  as text and shown as the date and time of the reader's own time zone (`formatInstant`).
- **The read lists of every shipped policy** name the eleven fields of the tab when the policy opens a template (`SolutionLoaderTest`).
- **The approvals of a template** (`TemplateApprovals` over `ApprovalHistoryList`) are one accordion item for each calendar day in the
  reader's time zone, newest first and open, older days closed. A day is one card with its approvals newest first; each has a title,
  the time of day, a note and the flow. `ApprovalHistoryList` has no other user.
- **Tests.** No database: `templateDetails.test.ts`, `columns.test.ts`, `ApprovalHistoryList.test.tsx`, `SolutionLoaderTest`; frontend
  `TemplatesPage.test.tsx` (the Details tab).

## Identification, Part A and Part B, the typed start and the numbering information (FA11B)

Decision: ADR-0206. One shipped object, the number patterns, two read endpoints, the template form; no migration.

- **The fields.** `category` is labelled **Identification** and has 26 values (the ten of the spreadsheet were added: `R`, `REHS`,
  `EHSAIR`, `EHSHIR`, `EHSAID`, `EHSHID`, `EHSAHID`, `PLEHS`, `EHSEMP`, `EHSOCP`). `partA` and `partB` are free text; `serial` carries the
  first serial of a new combination. `subCategory`, `partCode` and `planYear` are gone. Only the Approver policy edits `partA`, `partB`
  and `serial`; every policy that opens a template reads `partA` and `partB`.
- **The patterns** (`numbering.yml`): Part A is the process or customer segment (WI, SOP, F, F/EHS, CSF, PPT, L, L/EHS, R, R/EHS,
  EHS/AIR, EHS/HIR, EHS/AID, EHS/HID, EHS/AHID, PL/EHS), Part B the part code or the year (CP, PFC, FMEA use both, BP only Part B);
  IMS, QSP, CORP-QSP, ESSP, EHS/EMP and EHS/OCP have only the serial. Literal text of a pattern is at most 10 characters in all
  (`NumberFormat.MAX_LITERAL`).
- **The form.** The Document number builds itself as the person types (`TemplateNumberFields.tsx`): the serial box is typable for a
  combination that has no counter and disabled, with the next number, for one that has; a template that already has a number shows it
  while the identity is unchanged. Part A and Part B are drawn, in reading and in editing, only when the numbering information says the
  format reads them. The rows are in `templateDetails.ts`.
- **The Save.** `TemplateIdentityWrites`: parts are checked (capitals, digits, hyphen), a blank part the pattern reads means no number
  yet (the number is cleared), the typed serial starts a combination that has no counter, otherwise the counter's next number is taken.
  A serial typed for a running combination is ignored. The audit says that a number was assigned, never the number or a part.
- **The Numbering screen** lists the combinations that have a counter (read only, 50 to a page, `numbering.manage`).
- **Setup for an added identification.** Add the value in Setup, Objects, Template, Identification; it takes the default pattern (the
  identification, Part B when typed, a count) until a pattern is saved for it in Setup, Numbering.
- **Tests.** See ADR-0206; the browser script `platform-web/browser-checks/sheet-grid.mjs` creates its template with Part A.
