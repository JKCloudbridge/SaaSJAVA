# ADR-0195: Upload of a spreadsheet file into a sheet

- **Status:** Accepted
- **Date:** 2026-10-24
- **Track:** UI Alignment (UI3B), with one additive API endpoint and no migration
- **Related:** ADR-0172 to ADR-0174 (the sheet, its format and its limits), ADR-0186 (versions, the validator, the canonical
  form, "a library's format never reaches the database or the API"), ADR-0121 (files), ADR-0012 (what is never logged),
  ADR-0115 (who may see a record), ADR-0188 and ADR-0191 (the template page that shows the sheet)

## Context

An author builds a sheet by typing in the grid, in the form view or by pasting a block. People already have the content in
a spreadsheet and want to bring it in: "imagine a spreadsheet and someone uploads it: read the content and auto-save it as
your version along with the content". A file comes from outside the platform, so it is the one place where the sheet
meets input that somebody built to hurt it (a zip bomb, an entity attack, a huge or empty-looking sheet, a macro, a file
that is not what its name says), and its content is the organization's text.

## Decision

1. **Two kinds of file: .xlsx and UTF-8 .csv.** A workbook is the format people have; a text file is the format everything
   can write. The older binary format and a workbook with macros are refused with the way to save them. The kind is named by
   the person's screen and **checked on the bytes**: a zip is not accepted as text, a text file is not accepted as a
   workbook, whatever the name says. A text file in another encoding is refused with how to save it as UTF-8, because
   guessing an encoding changes people's words without a word. The separator of a text file is the one of comma, semicolon
   and tab that the first line holds most of.
2. **No new dependency: the reader is our own, over the JDK.** An .xlsx file is a zip archive of XML parts and a sheet needs
   six of them. Reading them with the JDK's zip and StAX readers keeps every limit in our hands, while reading, and adds
   nothing to the dependency scan or to what has to be kept patched. The alternatives, and why not:
   a full spreadsheet library reads far more than a sheet holds, is large, and has had zip-bomb and entity problems of its
   own, so every limit would have to be bolted on from outside; a small streaming reader does not give merged areas or column
   widths and cannot cap the unpacking as tightly. The cost of our own is that the parser is ours, so the hostile corpus
   (decision 6) is the thing to keep honest.
3. **What comes in, by the rules the author was told.** The first sheet of the workbook (the first visible one) is read.
   The first row with text names the columns (a choice the person can turn off in the preview; then the columns are named by
   position). Every text of the file is **fixed text** of the sheet; a cell with no text is left to be typed into. Widths and
   heights the file states come with it, bold, italic and the four named sizes come with a text, and merged areas come with
   it (a text under a merged area other than the top left cell is left empty and counted). A formula comes in as its last
   value, never as a formula; a number as the digits the file holds; a date as year-month-day (with the time when there is
   one); a yes or no as TRUE or FALSE. Everything else is **left out and counted in plain words**: images, charts, other
   sheets, links to other files, cells that held an error, merged areas outside the part that was read, touching the first
   row or overlapping another. Nothing is cut silently: a limit that the file goes over refuses the whole file and names the
   limit, with the row and column when a cell is the reason.
4. **The shape.** The first version of a sheet that has none is made in the shape where the sheet names its rows (dimension
   2, fixed). A sheet that already has versions keeps the shape of its first one (a sheet is not reshaped by an upload); for a
   sheet whose filler adds the rows only the names of the columns come in and the rows are counted as left out. An upload
   only **starts a new draft**: when a draft exists it is refused with the number of the draft, and an upload never changes a
   version, a draft included. (A person who wants the file in an existing draft pastes it into the grid, which already
   works.)
5. **Where and in what order it is checked.** The endpoint is `POST /api/v1/sheets/{ownerObject}/{ownerId}/versions/import`
   with the file as the whole body. Before the body is read: who may change the sheet (`SheetOwners`, the one place; not
   found, forbidden, in the same words as every other call), whether a draft can be started, the announced length (a
   proxy limit is still a deployment requirement). Then the bytes are read with a count (a body with no announced length is
   bounded too), the file is read **without a database transaction**, and only the last step takes the lock of the sheet,
   asks again and stores the grid. The grid passes `SheetGridValidator`, the same validator as every save; there is no
   second validator, and what the reader builds is the typed model, never a library's. Preview (`preview=true`) answers with
   the report and stores nothing. The file is **kept nowhere**: it is read from the request into memory and dropped; nothing
   is staged on disk.
6. **Hostile files are bounded while they are read.** Size of the file (5,000,000 bytes); the number of parts of the archive
   (100); the size each part unpacks to (20,000,000) and the whole archive unpacks to (100,000,000), **counted while
   inflating and for the parts that are skipped too**; the cell and row elements read (200,000, empty ones included, so a
   sheet of millions of styled empty cells is stopped); merged areas (the sheet's own limit); then the sheet's own limits for
   what comes out (500 rows, 100 columns, 1,000 characters a text, 200 a label). The XML is read as a stream with the
   document type declaration switched off and no external entity or schema ever resolved, so there is nothing to fetch and
   nothing to expand; the name of a part is only compared with a fixed list, never used as a path; nothing is executed, no
   macro is read, no link is followed. A text is held at most one character above its limit. At most four files are read at
   once on one instance (the others are told to try again). A refusal has a stable code and fixed words that name a limit by
   its number and a place by row and column, never a text of the file; a corpus of made-up hostile files
   (`sheets/hostile-spreadsheets.txt`) has a line for every code, and a test fails when a code has none.
7. **Never leaked.** The cells, the names of the columns, the name of the sheet and the name of the file are the
   organization's text: none reaches a log, an audit record, an event, a notice, a metric tag or an error
   (`SheetImportLogsAreCleanIT`). The audit record `sheets.version.imported` carries the format, the size of the file, the
   size and hash of the grid and counts. The browser shows the file's name to the person who chose it and sends it nowhere.
8. **"Last changed by" in the version list.** The list adds the display name of the person who last changed each version,
   from the base column `updated_by`, resolved through the same shared-kernel `ReferenceLabels` contract as the other lists
   (an active member only; a person who left and the platform's own actor give no name and the screen shows a dash; an
   identifier is never sent). The list still never reads a grid.
9. **The height of a row is dragged like the width of a column.** The format already holds a row height (16 to 400 pixels);
   the author's grid draws a handle on the bottom edge of a header row and a named row (not on the sample rows of a sheet
   whose filler adds the rows, not in a read-only grid, in annotate mode or in a fill-in), one drag is one change and one undo
   step, and a double click goes back to the default.

## Consequences

- Settings `platform.sheets.imports.*` (`max-file-bytes`, `max-part-bytes`, `max-inflated-bytes`, `max-entries`,
  `max-cells`, `max-concurrent`). No migration, no module edge. The version summary gains `updatedBy` and the limits gain
  `maxImportBytes`; both additions are optional for a client that does not know them.
- **Deployment requirement:** the proxy in front of the application sets a request size limit at least as large as
  `max-file-bytes` for this path (the application bounds the body by the announced length and by the bytes read; the proxy
  bounds the connection).
- **Left out on purpose:** download of the current version as a file (the same limits would apply); offering a column type
  read from the file (a number or a date column), which must never be applied silently; reading more than the first sheet;
  filling an existing draft from a file; the legacy binary format; styles beyond bold, italic and the four sizes.
- A change to the reader needs a line in the corpus for any new refusal and a case in `SpreadsheetImporterTest`.
