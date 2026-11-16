# ADR-0263: The author sets the kind of a cell, "Yes/No with a reason", attachment cells from the upload, letters and row numbers, grey and white cells

- **Status:** Accepted (the product owner answered every question of the SH5.1 plan with the recommendation on 2026-11-16)
- **Date:** 2026-11-16
- **Track:** Functional (sheet engine, a fix after the owner's walkthrough; not a numbered sprint). **No migration. No new setting. No new
  module edge. No new endpoint**: the stored format version 2 gains one kind of input cell and its prompt, fill format 3 gains one value,
  and the upload report gains one optional field on each place.
- **Related:** ADR-0251 (format version 2), ADR-0252 (formulas by identifier), ADR-0253 and ADR-0255 (the workbook reader), ADR-0254
  (attachment cells), ADR-0256 (drawing the look), ADR-0258 (fill format 3 and the reviewer's window), ADR-0115 (one place decides who sees a
  record), ADR-0012 (what is never logged)

## Context

The owner uploaded a real plant form as a template and found four problems. The upload put the yes/no answer of a question that holds a Yes
box, a No box and the words "If not represented, give reason" in the **next** cell to the right (which in that form is the column where a
file is to be attached). No author could make an attachment cell, because the editor had no way to choose the kind of a cell. The grid said
"Column 6" while the formula bar said `M8:M42`. And the two-tone corner mark of an input cell was not understood: the owner wanted fixed cells
grey and cells to fill white.

## Decisions

### 1. The author sets the kind of an input cell (a small part of SH6)

On a sheet in format version 2 that names its rows, the author's grid has **Input cell…**, which opens a popup (the shared `Modal`) with the
kind (Text, Number, Date, Yes/No, Yes/No with a reason, List with its values, Attachment with 1 to 5 files), Required and the class (filler or
reviewer). It writes the sheet's stored `inputs` for the chosen cell or block of cells (`lib/sheets/inputs.ts`); the server's validator judges
the document on save as always; the browser decides nothing. The kind of the cell where the focus is, is shown beside the toolbar. A sheet in
format version 1 keeps its column types and is not offered the popup.

### 2. A kind of its own: `boolean_reason`

The smallest additive shape is a **kind of its own** rather than a flag on `boolean`, because a value of another shape belongs to it and a
flag would have changed what an existing `boolean` cell may hold.

- **Stored format version 2:** `inputs[key] = {"type": "boolean_reason", "prompt": "..."}`. The prompt is optional, at most the length of a
  fixed text (5,000), no markup or control character, and **only this kind** takes one (code `input_prompt`). The cell holds no text of its
  own: the prompt lives in the statement.
- **Fill format 3:** the value is `{"answer": true|false, "reason": "..."}`. Either part may be missing, never both (an empty object is no
  value); an empty reason is dropped, so one meaning has one canonical text. The reason is bounded and checked like a text cell. Fill formats
  1 and 2 and every earlier format 3 value keep their bytes and hashes (no earlier value can have this shape, because the kind did not exist).
- **The reason is never compulsory.** Required on the cell means **the answer** must be given at Submit.
- **A formula sees the answer only** (`FillCalculations.operand`), exactly as it sees a yes/no cell. **The report keeps the answer only**, in a
  yes/no column, never the reason: it is free text.
- **Screens:** the template shows the prompt and a quiet "Yes / No"; the fill-in's form view shows Yes, No and the reason under the prompt
  (`ChoiceField`); the grid opens the same controls in a popup (`ChoiceDialog`) on Enter or a double click; view and print show the prompt,
  "Yes" or "No" and the reason. **Download:** the template's file writes the prompt as the cell's text. No fill-in is ever written to a file; if
  one ever is, the cell's text is the prompt, then "Yes" or "No", then the reason.

### 3. The upload: boxes on text make a Yes/No cell with a reason; the "beside" rule is gone

A check box (or a Yes box beside a No box) that stands on a cell holding text makes **that cell** a Yes/No cell with a reason, and its text
becomes the prompt. A box on a blank cell, or on one that holds only the words Yes or No, makes a plain yes/no cell as before. A box on a cell
under a merged area stands on the area's top left cell. The rule that took the next empty cell to the right is removed. A box that still has no
cell (outside the table, on a formula or other fixed content) is **listed by its place** for the author (`unplacedBoxPlaces`).

### 4. Attachment cells from the places of embedded files

Where a workbook held an embedded file, the form asked for a file there. The upload now makes that cell an **attachment cell** with the
defaults of ADR-0254 (up to 5 files, not required, filled by the filler) when the cell is inside the table, holds nothing (no text, no
formula, no other fixed content) and is not already a cell to fill of another kind (a check box on the same cell wins, because it says more).
A file on a cell under a merged area stands on the area's top left cell; two files on one cell make one attachment cell. **The file itself is
never kept.** Every other place stays listed. The report says how many cells were made (`attachment_cells`) and how many places were not
(`files_not_placed`); counts only. A sheet whose filler adds the rows has no such cell, and a sheet in format version 1 has no attachment cell
at all: every place is listed for both, as before.

**A formula that reads such a cell:** the cell was empty, so the formula read nothing; an attachment cell is never an operand, so it still
reads nothing and the answer does not change.

### 5. Letters and row numbers on a sheet in format version 2

The author's grid and the fill-in grid are headed **A, B, C…** and each row has its number **1, 2, 3…** in a band at the left. Both come from
one helper (`lib/sheets/addresses.ts`), built on the formula bar's own `letters` and counting rows as the formula bar does (the header rows,
then the named rows or the rows the filler adds), and a test holds every address to what `toExcel` writes. The column's stored name stays as
the tooltip and in what a screen reader says, and in the form view and the report. Nothing stored changes: a formula names its cells by
identifier. A sheet in format version 1 keeps its named headings and no band. Print and download are unchanged.

The band rides in the layer of the fixed columns (a sticky layer of no size inside each row), so it stays at the left edge while the rest
scrolls, works with frozen rows and the virtual window, and the number is part of its row for a screen reader (`rowheader`). Every column
starts after the band; the space the fixed columns may take is measured without it.

**Places in the upload report** are the file's addresses. When rows above the table (a title, the row that names the columns) did not become
rows of the sheet, the sheet's address differs; each place now carries `sheetRow` (null when it is no row of the sheet), and the screen says
"B12 in the file (B10 on the sheet)". The column letter is always the same, because the table always starts at the file's column A.

### 6. Grey and white

On a sheet in format version 2 a **fixed cell with no fill of its own is light grey** (the token `--sheet-paper-fixed`, defined once because the
sheet is on white paper in both themes; its contrast with the sheet's text, quiet text and accent is checked by `tokens.test.ts`) and a **cell
to fill is white**. A cell with its own fill, or one a conditional colour paints, carries `sheet-has-fill` and keeps that colour, so **the grey
never hides a colour** the author or a rule chose. Only an input cell that has its own fill keeps the small corner mark (it cannot be told apart
by grey and white); the mark is gone everywhere else. The hidden words "Input cell" for a screen reader stay. A sheet in format version 1 is
unchanged (the test that compares its class names cell by cell stays).

### 7. The paperclip popup in the fill-in grid

An attachment cell shows a paperclip and "(n attachments)". One click on it, Enter or a double click opens a popup with the **same attach
control** as the form view (`FillAttachments`): choose files, see their names, download one, take one off. It is read only when the person may
not change the cell (also in a read-only grid: the popup is how such a person sees the files). No new rule: the file service and
`FillFileRules` decide, and a removal still goes through the file service so the cell lets go of the file in the same transaction.

## What a new Revision does to an existing fill-in

A fill-in is pinned to the version it was started on and that version never changes, so a cell cannot change kind under an existing fill-in.
A new Revision may change a cell's kind; fill-ins started on it follow the new kind, and older fill-ins keep theirs.

## Never logged

A prompt, a reason, a cell, a file name or a file identifier is never in a log, an audit record, an event, a metric or an error (counts only;
`YesNoReasonLogsAreCleanIT`, `SheetImportLogsAreCleanIT`).

## Notes on earlier decisions

- **ADR-0251:** format version 2 gains the kind `boolean_reason` and its `prompt` (additive). The 123 golden hashes of format version 1 are
  untouched.
- **ADR-0254:** an author can now make an attachment cell (Input cell…), and the upload makes one where an embedded file stood. The grid has its
  own way in (the paperclip popup) beside the form view.
- **ADR-0256:** a fixed cell with no fill of its own is light grey and the corner mark is kept only on an input cell with its own fill; a sheet in
  format version 2 is headed by letters with a band of row numbers.

## Changes after the owner's second walkthrough (2026-11-16)

- **A number typed into a text cell is a number to a formula.** An uploaded form makes every column text, so a mark typed
  as `5` was text: `SUM` skips text (as a spreadsheet does) and gave 0, while `a+b` turned the text into a number. Now a
  cell to fill whose kind is text and whose value is a plain number (sign, digits, optional fraction) is handed to the
  calculator as a number (`FillCalculations.operand`), as a spreadsheet treats a typed number. A word or a date stays text.
  The calculator and the shared formula cases are unchanged. A score or rating that adds marks is right only from now.
- **The upload rule, confirmed by the owner:** a cell that holds a value in the file is fixed; every empty cell of the table
  is a cell to fill (check boxes and embedded files make their kinds as above). The author changes any cell afterwards with
  Fixed text or Input cell… before sending the template for approval.
- **A plain white fill is no fill for grey and white.** Workbooks often paint every cell white; such fixed text is grey now
  (`sheet-fill-white`). Any other fill, and a conditional colour, still wins.
- **No corner mark at all.** Grey and white tell a cell to fill from a fixed one; a screen reader still hears "Input cell".
- **A Yes/No cell with a reason** shows the answer on its top line (a quiet "Yes / No" until there is one) and below it the
  prompt followed by the reason.
- **View and print of a sheet in format version 2 have no band of column headings**: it prints as the form looks on paper.
  Version 1 keeps its named band.
- **The footer of a sent fill-in** gains a line "Submitted by <name> on <day>" below the number and Revision. The name is the
  fill-in's filler (only the person who started a fill-in may send it), named by the server through the directory of active
  members (`SheetFillSummaryView.submittedBy`, additive; null until sent and for a person who has left); the day is in the
  reader's time zone.
- **The screen no longer shakes on a formula cell:** the formula line keeps one height (it never wraps; a long answer is cut
  with the whole text as a tooltip).
- **Clicks:** a click on another cell while typing keeps what was typed and selects the clicked cell (a refusal keeps the
  cell open); rows no longer catch the pointer, so the whole of a merged cell shows the cell's pointer and takes a click.

## Changes after the third walkthrough (2026-11-16)

- **A number written in a fixed cell is a number to a formula too.** An uploaded form keeps every value, including its
  numbers, as fixed text, and the calculator read a fixed cell as its text, so `SUM` over uploaded numbers gave 0 (`SUM(B2:B3)`
  over 2 and 4 was 0). `SheetCalculator` now reads a plain number written in a fixed cell, or in the preset of a cell, as a
  number (the same rule as for typed marks, `FillCalculations.isPlainNumber`); the author's browser preview reads it the same
  way. Words and dates stay text. A test that had written down the old answer (0) was changed to expect 6.
- **The address of the selected cell before the formula bar**, as a spreadsheet's name box (`C6`), from the same
  `lib/sheets/addresses.ts` the headings and the formulas use.
