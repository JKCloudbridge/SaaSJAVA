# ADR-0257: Management Core: shared counters, publishing to places alone, the footer day and the owners of the new format

- **Status:** Accepted (the product owner asked for these four points in the MC1 prompt of 2026-11-13 and accepted the recommendations)
- **Date:** 2026-11-13
- **Track:** Functional (Management Core, part 1 of 3). No migration. One shared-kernel contract widened (the number series
  declaration gains `counterSeries`, the application's parts gain `options`). No new module edge.
- **Related:** ADR-0226 (applications of one kind on shared code; this is the second application made as files), ADR-0184 and
  ADR-0206 (number series and counters), ADR-0189 and ADR-0200 (who sees a template, the audience an approval step suggests),
  ADR-0201 (the footer row), ADR-0251 (the new sheet format and its switch), ADR-0012 (what is never logged)

## Context

Management Core was an announced tile. It becomes a real application: a management user makes a form (a template) with a sheet,
sends it for approval, has it numbered, and publishes it to the plants; the plant heads see it. The functions are those of the
other applications, so the recipe of ADR-0226 applies: three objects, a numbering file, a bundle, six access policies, all data.
Four things in the request are not in that recipe.

## Decisions

### 1. The numbers continue the Document counter: a series may draw its counters from another series

A counter belongs to `(series key, scope)` (ADR-0184). A series of its own would start every combination again at 1, so a form
and a document could both be `WI/PRD/01`. Three ways were weighed: (a) the series shares the counters of another series; (b) a
series of its own whose counters an administrator raises by hand to the Document counter's last value; (c) only the patterns
are shared. **(a) was chosen.**

`numbering.yml` may say `counterSeries: <key of another series>`. The series keeps its own object, its own patterns and its own
organization settings; only the row of the counter is read and written under the other series' key. The scope (the selector
value and the values the pattern reads) is derived as before, so the same combination is one counter and a combination only one
pattern reads has its own. The catalogue refuses, at start, a series that names itself, a series that does not exist and a chain
(a series that shares must share with one that has counters of its own). Reservations and the check "this number is used" stay
under the series' own key and its own object, because the number field is unique inside each application's own template object.

Why not (b): it is manual for each combination, and a combination that Document Management first uses after the raise would clash
without warning. Why not (c): the counters would still restart.

What this does not do: it does not make a number unique across the two objects by itself (nothing does; they are different
tables of records); it makes two applications draw different serials from one counter, which is what the owner asked for. A
pattern that reads no counter (the typed ones) is not shared, because it has none.

### 2. Publishing to places alone is a bundle option, not a branch in the code

Document Management publishes to a Location, then a Department (`DepartmentGroup`). For Management Core a plant is a Location and
the people who receive a form are the plant heads. The solution file says so as data:

    options:
      publishTo: places
      publishDepartment: PLANTHEAD

The department "Plant head" is part of the organization's own Department list (the starting list now has it under Plant 1 and
Plant 2), and an administrator keeps one Department group record per plant naming that plant's head group. The audience options of
the publish dialog then list the places that have the department, with the one department at each, and say `placesOnly`; the
dialog shows tick boxes for places and sends the fixed department; the server **refuses any other department** for such an
application. A place with no group is offered and marked, as today, and the publish says no for it. Nothing else changes: the
same groups, the same `RecordShares`, the same memory of what a publish shared (`inspection_publication`).

Rejected: a new object for plants (a second place to keep in step with `DepartmentGroup`); a branch on the application's name in
the code (the generic code names no application).

### 3. The footer carries the day of the Revision, in one text rule on both sides

The footer row stays what ADR-0201 made it (the number, two spaces, `Rev No.'00'`). An application whose bundle says
`footerDate: "true"` gets a second line below it: `Date: 13-Nov-2026`, the day the Revision in use was published.

- **Where the day comes from:** `lastRevisedAt`, which the platform stamps on the template record when a Revision is published
  (FA8). It is known only for the Revision in use; a Revision shown from the history has no stored day, so its footer is the first
  line alone. Not a date a person typed.
- **Which zone:** the screen and the print use the reader's own time zone (the browser knows it). The server does not know the
  reader's zone and the organization has none, so the file uses one setting, `platform.inspection.footer.zone` (default `UTC`).
  A reader near midnight can therefore see a different day on the screen and in the file. This is stated in the guide.
- **Drawn as a second row** in all three places (the grid, the print view, the workbook), not a line break inside one cell, because
  a cell with a break needs a different height in each drawing. The text is `first line` + newline + `second line`; the three
  drawings split on the newline.
- **One rule, one file of cases:** `TemplateSheetTexts.footer` on the server and `footerTextWithDay` in `lib/sheets/revision.ts`
  both read `src/test/resources/inspection/footer-cases.txt` (number, revision, instant, zone, expected text), the rule of ADR-0201.
- **Other applications are untouched:** without the option nothing changes (the lifecycle view says `footerDate: false`).

### 4. Which owners make the new sheet format when a file is uploaded

The switch `platform.sheets.format-two.enabled` is global, and with it on every upload made a version 2 sheet, which a fill-in
refuses until fill format 3. A setting `platform.sheets.format-two.owners` (a list of object API names, empty means every owner)
narrows that: an upload for a record of another owner makes the sheet it always made. The `local` profile lists
`ManagementTemplate`, so a developer keeps the switch on and still fills in the forms of the other applications. The list is a
setting, not a bundle flag, on purpose: the existing tests that upload for the Quality Management System's template expect the new
format while the switch is on, and a bundle flag would have had to change them. A bundle flag remains possible later.

### 5. Options of an application are plain data, checked by the owner of the functions

A solution file may carry `options:` (names in lower camel case, values short plain words). The solution module only carries them
(`ObjectRoles.options`); `RecordSets` knows the three names it reads (`footerDate`, `publishTo`, `publishDepartment`), refuses any
other name and a `publishTo` without a department at start, and names the application in the message.

## Consequences

- Management Core is files plus these four small changes. A fourth application of the kind is still a folder.
- The access policies have their own names (Management Author, Approver, Publisher, Reader, Filler, Reviewer); holders of other
  applications' policies get nothing in it, and the reverse. The department setup (`DepartmentGroup` and its policy) is shared.
- A fill-in on a Management Core form is refused until SH5 (fill format 3): MC1 ends at "published and visible to the plant
  heads". The review loop, the score and the dashboard are MC2 and MC3.
- `EnvironmentParityTest`'s sibling `ManagementCoreParityTest` keeps the copied objects in step with the Quality Management
  System's.

## Tests

No database: `NumberSeriesCatalogueTest` (sharing), `NumberingLoaderTest`, `RecordSetsTest` (options), `PlacesOnlyAudienceTest`,
`TemplateSheetTextsTest` (with `footer-cases.txt`), `SheetPropertiesTest`, `ManagementCoreParityTest`, `StandardMetadataLoaderTest`,
`SolutionOrderAndSharingTest`; frontend `revision.test.ts`, `SheetGridFooter.test.tsx`, `SheetPrintView.test.tsx`,
`PublishDialog.test.tsx`, `ApplicationGrid.test.tsx`. Real PostgreSQL: `SharedCounterIT`, `ManagementCoreIT` (the gate matrix and the
separation, from `ApplicationOfTheKindTests`), `RetainedObjectsIT`.
