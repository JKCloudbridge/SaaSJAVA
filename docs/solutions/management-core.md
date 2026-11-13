# Management Core (key `management-core`)

Decisions: [ADR-0226](../adr/0226-applications-of-one-kind-on-shared-code-with-their-own-objects.md) (applications of one kind made of
files) and [ADR-0257](../adr/0257-management-core-shared-counters-places-only-publishing-the-footer-day-and-the-owners-of-the-new-format.md)
(what is particular to this one). The fifth tile of the grid. Management Core part 1 (MC1) made it real: a management user makes a
form with a sheet, has it approved and numbered, and publishes it to the plants. It is made of files
(`platform-app/src/main/resources/solutions/management-core/`) over the code the other applications of the kind share.

## What it is

The same functions as the [Quality Management System](inspection.md) and [Environment Management](environment-management.md): templates
(here called forms) and their sheets, approval, numbering, publishing, Revisions through change requests, fill-ins, the report and the
home dashboard, over **records of its own**. A form made here is never listed in another application, and the reverse.

| Part | Name |
|---|---|
| Template (the form) | `ManagementTemplate` |
| Fill-in | `ManagementFill` |
| Change request | `ManagementChangeRequest` |
| Number series | `management-document` (draws its counters from the series `document`) |
| Plan feature | `management-core` (console name: Management Core), in no plan by default |
| Access policies | Management Author, Management Approver, Management Publisher, Management Reader, Management Filler, Management Reviewer |

Tabs: Home, Document Control, Change requests, Record Control, Report, KPI management (announced), Management Review (announced),
Plantwise Review (announced) and the Department group entry.

## What is particular to it

- **Numbers continue the Document counter.** `numbering.yml` says `counterSeries: document`. A form and a document of the same
  combination (for example `WI` and Part A `PRD`) take consecutive numbers from one counter. The patterns are this application's own:
  an administrator sets them in Setup, Numbering (the series `management-document`); the counters shown there are the shared ones.
- **Publishing goes to plants.** The bundle says `publishTo: places` and `publishDepartment: PLANTHEAD`. The Publish dialog lists the
  places (locations) and asks for no department; the form goes to the group named by the plant's Department group record for the
  department "Plant head". See "Plants" in [metadata-guide.md](../metadata-guide.md) section 49 for how an administrator sets plants up.
- **The footer carries the day.** `footerDate: "true"`: below `number  Rev No.'00'` the sheet shows `Date: 13-Nov-2026`, the day the
  Revision in use was published, on the screen, in print and in the downloaded file. The screen and the print use the reader's time
  zone; the file uses `platform.inspection.footer.zone` (default `UTC`). A Revision of the history has no stored day, so its footer
  is the first line alone.
- **Forms are sheets of the new format.** An upload for a `ManagementTemplate` makes a format version 2 sheet (the look, formulas,
  Yes/No cells) when `platform.sheets.format-two.enabled` is on; the `local` profile lists `ManagementTemplate` in
  `platform.sheets.format-two.owners` so the other applications keep making sheets that can be filled in.

## What it cannot do yet

A plant head cannot **fill in** a form: a fill-in on a format version 2 sheet is refused until fill format 3 (SH5). So MC1 ends at
"published and visible to the plant heads". Reviewers per plant, the monthly fill-in, the score, the 85 percent rule and the approval
chain to the top are MC2; the dashboard is MC3.

## For administrators

- **Switch it on:** give the plan feature `management-core` in the console (a plan, or an organization override). Taking it away hides
  the application at once and deletes nothing.
- **Assign the policies** to people or groups. Holders of other applications' policies get nothing here.
- **Set up the approval process** for `ManagementTemplate` (and `ManagementChangeRequest`) in Setup, Approvals: none is shipped.
  Without one, Submit is refused.
- **Plants:** see [metadata-guide.md](../metadata-guide.md) section 49.

## For contributors

The three objects are copies of the Quality Management System's on purpose; `ManagementCoreParityTest` fails the build when the fields
the functions read drift apart. No Java or web class names one of them (`InspectionArchitectureTest`). The tests of the lifecycle,
the gate matrix (feature on, off, plan, organization override, cross-tenant, forged host, platform administrator, not signed in)
and the separation from the other applications are `ManagementCoreIT` (the battery of `ApplicationOfTheKindTests`); the shared
counter is `SharedCounterIT`.
