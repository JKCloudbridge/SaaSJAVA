# ADR-0250: A spreadsheet comes in as it looks: borders, blank cells inside the table and the title row (FA14)

- **Status:** Accepted
- **Date:** 2026-11-02
- **Sprint:** FA14 (Functional track, not a numbered sprint)
- **Related:** [ADR-0186](0186-the-sheets-module-versions-the-owner-seam-and-the-request-size.md), [ADR-0195](0195-upload-of-a-spreadsheet-into-a-sheet.md)

## Context

The upload of ADR-0195 kept only the cells that have text, and took the size of the grid from the last cell with text. A real
form showed two gaps. The empty answer areas of the form, drawn as bordered boxes to the right of and below the last text,
were cut off, so the sheet came in smaller than the file looks. And the title of the form, one merged cell across the page,
became the name of column 1 while the other columns were called "Column 2", "Column 3", because the box "The first row holds
the names of the columns" was ticked by default.

## Decision

1. **A cell with a border on any side is part of the table.** The reader now reads the list of borders of the styles part
   (`borders`) and the border of each cell format (`borderId` of `cellXfs`), and notes every cell that has a border and no
   text (also a cell that held an error). A side counts when it names a line style other than none; a diagonal line does not;
   a border inside a conditional format (`dxfs`) is not a cell's. "Any side" was chosen over "boxed on all four sides" because
   forms are often drawn with a line below each answer or between rows only, and a stricter rule would cut those.
2. **The table is the bounding box of the cells with text, the cells with a border, and the whole of every merged area that
   holds a cell with a border** (`RawSheet.extent`). A blank cell with no border never extends it, so the empty area around a
   table stays out. Blank cells inside the box are cells to be filled in, as every empty imported cell already was. A bordered
   blank row in the middle or at the end is a row of the sheet. A merged area with no bordered cell and text only in its first
   cell keeps the old rule (it is left out and counted when it reaches beyond the table). A bordered area under a merge is
   counted once (the merge is one cell).
3. **Not read, on purpose.** A border given to a whole row or a whole column as a *style of that row or column* (the file does
   not list its cells) is not turned into cells: the reader would have to invent up to 16,384 columns or a million rows. Only
   cells the file lists count. The left and top edge are unchanged: the columns start at the first column of the sheet and the
   rows at the first row with text or a border.
4. **A file bordered all over is refused by the limits, never cut.** The cell and row elements read are still counted
   (`max-cells`, 200,000, bordered blank cells included); a bordered blank cell in a column past the sheet's limit (100) is
   refused at once, with the number, so the reader never holds more than the sheet may have; the builder then checks the
   rows (500) and columns (100) of the whole table and says the number. Nothing is silently cut. The record of bordered
   cells is one bit per column in a row, so memory is bounded by the element count. The list of borders itself is bounded at
   64,000 (the most cell formats a workbook can have): one more is refused with the code `styles`.
5. **The title row.** The first row of the table is a **title** when its only text is in the first column and that cell is
   one merged area of that single row that spans **the whole width of the table**, which is at least two columns. A merge over
   part of the width is an ordinary row (a "whole width only" rule was chosen over "most of the width", which would need a
   cut-off number and would guess). A title is kept in the body as the first row, as fixed text with its bold and size, with
   its merge; it does not become a column name. The grid's header-row band was not used: it is a separate feature (bands above
   the body, with their own rules for columns) and the body keeps the file's order and the merge.
6. **The server decides the starting value of the box.** `firstRowLabels` on the upload is now optional. Left out, the server
   answers "no" for a file whose first row is a title and "yes" otherwise (as before), and the report carries two optional
   fields: `firstRowLabels` (the choice applied) and `firstRowIsTitle`. The screen sends nothing on the first look, sets the
   box from the answer, says "The first row looks like a title" in words, and sends the choice explicitly from then on and when
   saving. The person can tick the box; the title then names the first column, as before. An older server that sends neither
   field leaves the box ticked. A text file has no merges: it is never a title and its behaviour is unchanged.
7. **The preview says what came in.** Two notes with counts only: `bordered_blanks` ("N blank cells with a border were kept as
   cells to be filled in", counted in the rows that become the body, a merged area as one) and `title_row` ("1 title row was
   found (it is kept as the first row of the sheet and does not name a column)"). They are "came in" notes, shown under
   "Changed on the way in". Nothing of the file's content is logged, audited or put in a metric.

## Consequences

- No migration, no module edge, no new setting. The report view gains two optional fields (additive); the OpenAPI document
  and the TypeScript client were regenerated. The sheet's validator is unchanged and still runs on every result.
- A workbook the platform wrote itself (download, then upload) writes every cell of its table with a border, so it now comes
  back with its blank cells instead of losing the ones at the edge.
- A hostile `borders` part is bounded (64,000) and counted with the rest; a new refusal code `styles` has a line in the corpus.
- **Left out on purpose:** borders given as row or column styles (3 above); a different rule for diagonal lines; offering the
  second row as the names of the columns when the first is a title; trimming empty leading columns on the left; a border
  colour, thickness or style (the sheet records none).
