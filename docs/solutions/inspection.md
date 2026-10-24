# The Audit maintenance solution (key `inspection`)

The first platform-shipped solution (Functional track). This page is for two readers: the **platform administrator and organization administrator** who switch it on and use it (section 1), and the **contributor** who extends it in the next sprints (section 2). The framework behind it is in [ADR-0181](../adr/0181-the-solution-framework.md); the decisions about the cluster itself are ADR-0172 to ADR-0180. The word "audit" in code still means the audit trail: the cluster is called `inspection` in code and "Audit maintenance" for people (ADR-0180).

## 1. For administrators

### What exists in this release (FF1): fill-ins

A person can now **fill in** a template that was published to them. The decisions are in ADR-0192.

1. **Start.** On the page of a template that has a version in use, a person who holds the **Filler** policy and is in a department it was published to sees **Start a fill-in**. The fill-in is made on the version in use and stays on it for ever. A template with no version in use, or a retired one, starts none.
2. **Fill.** The fill-in opens in the **Fill-ins** page of the application (also reachable from the template: **See the fill-ins of this template**). Only the cells the template marks as input take a value; each value is checked by the type of its column; rows can be added where the template allows. **Grid view** on a wide screen, **Form view** on a phone. Save as often as needed; a half-done fill-in can be saved.
3. **Send and review.** The ordinary **Submit for approval** of the fill-in's page. A required value missing, or no row where the filler adds the rows, is refused in plain words. The administrator builds the approval process on **Fill-in** (`/setup/approvals`); a rejection returns the fill-in to its filler, an approval fixes it.
4. **Who sees which.** The filler sees their own. **Approver** and the new **Reviewer** policy see every fill-in of the organization. A person asked to review reads that one fill-in.
5. **After it was sent.** Only the people who hold the **Approver** policy can correct it; each correction is counted and recorded without its content.

**What the organization's administrator sets up for fill-ins.** See `Sprint FF1 manual steps.md`: give **Filler** to the people who fill in (they must also be in a department the template is published to), **Reviewer** to the people who only need to see every fill-in, and build the approval process on Fill-in.

### What exists in this release (FA5): change requests

A person who can read a published template may now **ask for a change** without being able to edit it.

1. **Ask.** Open the template (Audit maintenance, Templates). Under the sheet, **Ask for a change**: stand on a place in the grid (a click on a cell or on a column label, or the arrow keys; **Enter** on a cell goes to the box for the comment), choose **this cell, this row, this column or the whole template**, write the comment and add it to the request. Give the request a title and send it. The sheet cannot be changed from here and the request changes nothing in it.
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

See `Sprint FA4 manual steps.md` for the steps in order. In short: give the policies **Author**, **Approver**, **Reader** and **Department setup** to the right people, roles or groups; build the approval process on Template; make one **public group** for each department and put its people in it; and add one **Department group** record for each Location and Department that names that group (Audit maintenance, Department groups). A department with no record, or with an empty group, cannot be published to and the dialog says so.

### What existed in FA3

Since FA3 a template has a **sheet**: open **Templates** in the Audit maintenance application, open a template (or choose **New template**), start its first version, build it in the Form view or the Grid view, save it and keep its versions. The full description for the administrator is [metadata-guide.md](../metadata-guide.md) section 27. In this release a version is only ever a **draft**: submitting for approval, the number, publishing and retiring come next (FA4A and FA4). The page lists the templates plainly (no filters, no lifecycle summary: the master list is FA4).

### What existed in FA2A

The framework (FA1) and, since FA2A, the first fields of the object and the numbering. The templates themselves, the grid, the approval and the publishing come in later sprints (FA3 to FA5).

**The object Template now has** a **Category**, a **Sub-category** (only the sub-categories that go with the category can be chosen) and a **Number** that nobody types. The lists of categories, sub-categories and which go together belong to the organization: the platform ships a starting list, the administrator changes it in the object manager (Object settings, Template, Fields, open the field, **Edit the values**), and a member who holds the ability **Add values to picklists** can add a new abbreviation from the form. The number is written in the format of the category (for example `WI/PRD/03`) and counts up for each category and sub-category, from 1; a newly added abbreviation starts at 1. The number is given by the platform when the template is approved, in a later sprint; in this release nothing gives a number yet. The starting list is a placeholder (the 16 categories of the product owner's scheme and a few generic sub-categories) and is replaced by the product owner's real list as a data-only change.

### What existed in FA1

Only the framework and its first object. An organization that has the feature sees an application called **Audit maintenance** in the application launcher, with one page, **Templates**, that says "nothing here yet". The object **Template** (`InspectionTemplate`, a name and a "retired" flag) exists and can be read through the usual record screens and API by a member who is allowed. The templates themselves, the grid, the approval, the numbers and the publishing come in later sprints (FA2A to FA5).

### How to give an organization the feature

1. Sign in on the platform address as a platform administrator and open the console.
2. Either add the feature **Audit maintenance** to a plan (Plans, open the plan, Change, tick the feature) or give one organization an override (Organizations, open it, Features, "Switch on"). There is no licence to buy and no quantity to set.
3. Taking the feature away shows the platform's own words first: the application and its objects are hidden at once, **nothing is deleted**, and everything comes back when the feature is given again.

### What the organization's administrator does

1. The five access policies **Author**, **Approver**, **Reader**, **Filler** and **Department setup** appear in Access policies on their own, marked "Comes with the platform", the first time anyone in the organization opens a page after the feature was gained. They can be given to members and groups; they cannot be changed or removed. They use no licence.
2. Until a person holds one of them they see nothing of the application. Since FA4 the policies hold the real rights (create and change a template, view every template, publish, retire, correct a version in review), described in section "What exists in this release (FA4)". Which people hold which is the administrator's decision: give **Author** to the roles that write templates and **Approver** to the roles that approve and publish.
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
