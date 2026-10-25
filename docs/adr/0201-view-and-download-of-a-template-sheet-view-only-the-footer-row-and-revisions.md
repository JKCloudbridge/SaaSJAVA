# ADR-0201: View and Download of a template sheet, "View only", "Allow download", the footer row and Revisions

- **Status:** Accepted
- **Date:** 2026-10-25
- **Track:** Functional (FA7, demo readiness), with one additive migration (V079), three additive endpoints of the cluster, one additive
  entry point of the generic sheets module and one generic print view in the web application. No new module edge, no new ability, no
  new policy.
- **Related:** ADR-0186 (sheets and versions), ADR-0195 (the reader of spreadsheet files, whose counterpart this adds), ADR-0188 and
  ADR-0199 (publishing, and who publishes), ADR-0198 (the template number), ADR-0192 (fill-ins), ADR-0115 (who may see a record),
  ADR-0012 (what is never logged)

## Context

People who read a template want to see it the way a spreadsheet program shows it before printing, to print it or save it as a PDF, and,
where the organization allows it, to keep a copy as an Excel file. Plant QMS decides, when it publishes, whether a template is only a
reference (nobody starts a fill-in from it) and whether its sheet may be downloaded. Every template sheet ends with a footer row that
carries the template number and the revision, as on the paper form. People speak of **Revisions**, starting at 00, not of versions.

## Decision

1. **The two options are kept in a table of their own** (`inspection_template_option`, V079): `view_only` and `allow_download`, one row
   for each template whose options were ever set, **per template and not per revision**. A template with no row has both off, which is
   what every template made before this release means, so there is no migration of old values. They are not two fields of the template
   record because the record is locked for everyone while a revision waits for approval, and Plant QMS publishes (and may change the
   options) exactly then; the options are not part of what is approved. The database lets a row in or change it **only** when the
   transaction setting `platform.template_option_write` is on, which only `TemplateOptionStore` raises in the statement itself; a row
   never moves to another template, is never deleted or soft-deleted, and a template has at most one live row. Tenant-scoped with forced
   row level security. Nothing but two yes or no values is stored.
2. **Who sets them.** Publish takes the two values (`viewOnly`, `allowDownload`, a value left out keeps what the template has), and
   `PUT /api/v1/templates/{id}/options` changes them later without publishing again. Both need the ability `sheets.publish` (the Publisher
   policy and the Organization administrator), asked on every change. The audit record carries the template's identifier and the two
   values (`inspection.template.options.changed`, and the same two attributes on `inspection.template.published`), never a name.
3. **View only stops new fill-ins only.** `FillIns.start` refuses in fixed words ("This template is view only, so no fill-in can be
   started from it.", `409`) and `fill-in-options` says `canStart: false` with that reason, so the screen does not offer the control. It
   does not hide the View button, does not stop people reading the template and does not touch a fill-in already started: that one can
   still be saved, sent and reviewed.
4. **Download is decided by the server.** `GET /api/v1/templates/{id}/download[?versionNo=]` asks, in this order: may the caller see the
   template (`TemplateAccess.visible`, "not found" alike for everybody who may not, another organization, the platform host and an
   unknown identifier); is download allowed (the option, **or** the ability `sheets.publish`, so Plant QMS and the administrator can
   check their work whatever the box says; otherwise `403` with fixed words); may the caller read that revision (`SheetExports`, which
   asks the rule of the Sheet tab and nothing else). The button follows `canDownload` and `canSetOptions` of the lifecycle view; a
   hidden button is not the rule. The audit record (`inspection.template.downloaded`) carries the template's identifier, the revision
   number and the size; never the number, the file name or a cell.
5. **The Excel file is written by us over the JDK, no library** (`XlsxWriter`, the counterpart of the reader of ADR-0195). The sheet as
   people read it: the band of column labels, the header rows, the rows (or, when the filler adds the rows, the same ten blank sample
   rows the screen shows), every cell bordered, merged areas, column widths and row heights, bold, italic, underline, the four sizes,
   alignment and the named text colours, and the footer row. **The input cells of a template are written empty.** Every cell is an inline
   string: a text that starts with `=`, `+`, `-` or `@` is never a formula (there is no formula element in the file) and carries the
   quote prefix as well; characters XML cannot hold are dropped; the workbook has the six parts a workbook needs and no macro, link,
   image or external reference; the size is bounded (5,000,000 bytes, and the sheet itself is bounded by its own limits). The page is A4,
   fitted to the page width, landscape when the columns add up to more than 720 pixels, with the column labels and header rows repeated
   on every printed page. The file is named after the template number and the revision (`F-MR-16_Rev00.xlsx`; a slash of the number
   becomes a hyphen and nothing a file name should not hold survives). Cell fills do not exist in the sheet format (only text colour),
   so none are written.
6. **The sheets module gains one cluster-only entry point**, `SheetExports.xlsx(owner, id, version, footer)` (an architecture test names
   the cluster as its only caller, like `SheetLifecycle`). The module knows no business word: the cluster decides whether a file may be
   given and what the footer says. The one rule of who may read a version moved into `SheetVersions.readable`, which the Sheet tab's read
   and the download both use, so there is never a second rule.
7. **The footer row is made by the platform when the sheet is shown, printed and downloaded. It is not stored in the author's grid.** So
   an author cannot overwrite it, the canonical form and the hash of the stored grid and the review rules do not change, and the number is
   always the current one. It is one row across the full width, bordered like the rest, with the text at the right of the last column:
   the template number, two spaces, `Rev No.` and the revision in single quotes, for example `F/MR/16  Rev No.'01'`. Until the template
   is numbered (the first approval) the row is there and blank. **The revision in the footer is the sheet's active revision**
   (the one in use), and the revision being shown while none is in use yet; FA8 (change requests) will give the first new revision the
   next number through the same rule. It is drawn in the grid of the Sheet tab, as a closing line of the form view, in View and in the
   file. The number comes from the lifecycle view of the template (read by the platform, so it is shown to everybody who can read the
   template), the revision from the lifecycle numbers.
8. **View is a generic print view in the web application** (`SheetPrintView`, `lib/sheets/print.ts`): drawn from the sheet the person
   already reads, with no request, the input cells empty, the sheet fitted to the page width, the column labels and header rows in the
   table head so the browser repeats them, a page that is white in both themes, A4 portrait or landscape by the same width rule as the
   file, and a Print button that opens the browser's print dialog (which also saves a PDF). While it is open a class on the body makes
   printing show only it. It is offered to **everybody who can read the template**, in the bar of an open revision.
9. **Revision instead of Version, starting at 00, going forward only.** The stored numbers and the API's field names are not changed and
   not renumbered. Everywhere a person reads the word for a sheet or a template it is **Revision**, shown as the stored number minus one
   with two digits (1 shows 00, 2 shows 01, 101 shows 100): `app.platform.sheets.Revisions` for the sentences of the server and the
   footer, `lib/sheets/revision.ts` for the screens, with the same cases tested on both sides. The words of lists, headings, the revision
   list, the Publish popup, the approval history ("New revision 01"), the change-request screens, the fixed refusals of the server and
   the guides were changed. The versions of an approval process, a workflow and a record are not sheet revisions and keep their word.

## Alternatives considered

- **Two fields on the template record.** Rejected: the record is locked during an approval and Plant QMS publishes then.
- **Store the footer row in the grid.** Rejected: the author could overwrite it, the grid's hash and the review rules would change with
  every number, and a renumbered or republished template would have to rewrite every revision. Made at show time it is always right.
- **A spreadsheet library for the file.** Rejected: a library's format would reach the download path; the JDK's zip stream and a few
  XML parts are enough, and every limit stays in one place (ADR-0186, ADR-0195).
- **A server-made PDF.** Not needed: the browser's print dialog saves a PDF from the print view.

## Consequences

- `SolutionBoundaryTest` still passes: the cluster's words are in the cluster's places, V079 is added to that list; the generic web code
  (`SheetPrintView`, `print.ts`, `revision.ts`) carries none.
- A template made before this release has both options off: nobody but the people who publish can download it, and fill-ins can be
  started as before. Plant QMS ticks the boxes in Sheet options.
- The footer reads the active revision. Until FA8 there is one revision in use at a time, so this is the revision people hold.
- A hand-made `.xlsx` that a person uploads comes back through the reader of ADR-0195 as before; the footer row is never part of an upload
  because it is not in the stored grid.
- Rows of the file beyond the sheet's own limits cannot occur: the file is made from a stored grid, which the limits already bound.
