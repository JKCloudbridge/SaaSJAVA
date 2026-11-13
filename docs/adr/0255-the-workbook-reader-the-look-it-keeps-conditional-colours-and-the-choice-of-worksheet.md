# ADR-0255: The workbook reader, the look it keeps, conditional colours and the choice of worksheet (SH1B)

- **Status:** Accepted by the product owner on 2026-11-13 (the plan of SH1B and its eleven answers)
- **Date:** 2026-11-13
- **Track:** Functional track, sheet engine part 2 of 2 (not a numbered sprint)
- **Related:** [ADR-0251](0251-sheets-format-version-2-and-the-engine.md) (the format this reader fills), [ADR-0253](0253-workbook-files-through-a-library-reading-by-stream-guards-first-and-what-it-reverses.md) (the library and the guards; this record is its "Built" detail), [ADR-0195](0195-upload-of-a-spreadsheet-into-a-sheet.md) (the order of the checks stays), [ADR-0250](0250-a-spreadsheet-comes-in-as-it-looks-borders-blank-cells-and-the-title-row.md) (the table, bordered blanks and the title row stay), [ADR-0254](0254-attachment-cells-a-list-of-file-identifiers-of-this-fill-in.md), [ADR-0012](0012-observability.md)

## Context

SH1A stored a sheet in format version 2 but nothing produced one: the upload still read the first worksheet only, kept four sizes and bold and italic, refused a whole workbook for one long note, and
accepted an archive that names a part twice. SH1B builds the reader that makes a version 2 sheet look like the file, with the guards first and the library second (ADR-0253). This record states the
decisions that building it took, and one change to the format that the proof had not noticed: **SH1A's format had no place for a conditional colour**, though ADR-0251 listed it.

## Decision

1. **The library reads styles, not worksheets.** The styles part and the theme are read from bytes by the library's `StylesTable` and `ThemesTable` (it resolves theme colours, tints and the table of indexed colours, which
   is the part worth not rewriting). No package object, no whole-workbook object and no file is opened (an architecture test fails if a class of the sheets module names `OPCPackage` or opens a file). The
   worksheet itself is read by a stream reader of ours, because only a reader of ours gives, in one pass, the style number of each cell, the merged areas, the panes, the page set-up, the conditional colours
   and the guards on each formula. Classes: `XlsxArchive` (our scan), `StyleBook` (the library, behind a mapping), `WorkbookReader` (the pass), `ExtrasReader` (check boxes, links, shapes, embedded files).
2. **The order is fixed and every guard of the old reader is kept**: announced length, bytes read with a count, then our archive scan, then the styles (after our own count of each kind of element, 64,000), then the
   worksheet with its counts. The scan adds what the old reader lacked: a part name that appears twice (also when it differs only by case) is refused (`duplicate_part`), a name with `..`, a back slash, a leading
   slash or a control character is refused (`unsafe_part_name`), the number of parts and the size of each and of the whole are counted while inflating (also for skipped parts), a macro part or a macro content
   type is refused. A part that declares a document type is refused outright (`doctype`). A formula longer than 4,000 characters (`formula_too_long`) or nested deeper than 32 (`formula_too_deep`) is refused
   **as it streams past**, before any parser sees it; a formula still comes in as its last value, with a note (SH2 stores it). The library's own limits are set once, on first use, in one place (`PoiLimits`),
   and its logger `org.apache.poi.openxml4j.opc` is off.
3. **The look is mapped, never copied.** A font family is compared with a fixed table of 17 names (exact or by alias) and otherwise takes a generic key by a few class words; the name is never stored, used or
   logged, and the unknown ones are counted once each. A colour is `#rrggbb` after the theme and the tint are applied; plain black text is not kept (the page already draws it, and a screen picks an ink for a
   fill); only a solid fill is a fill. A border style is mapped to the six of the list (hair is thin, the dashed-dotted kinds are dashed, the medium dashed kinds are medium). A number format is one of the ten keys
   by a few patterns after locale blocks and padding are removed; **a format with a literal in it (a currency sign, a unit) is not a close match and is reported as unknown**, the number kept. A date or time
   format is a date: the cell comes in as its text, as before, and the key is only a hint. Each part of a style is read separately, so a workbook written by hand or by a tool that leaves out a list still gives
   the parts it has.
4. **Conditional colours are in the format now.** A sheet may carry `conditions`: at most 50, in the order they apply, each a range (two cell keys), a comparison (`LT`, `LTE`, `EQ`, `NEQ`, `GTE`, `GT`, `BETWEEN`,
   `NOT_BETWEEN`), one or two plain numbers written as decimal text, and a fill and/or a text colour. It is a closed rule: no formula, no reference, nothing is run. Only a sheet that names its rows (dimension 2)
   has them. The upload keeps a "cell value against a number" rule and **counts every other kind and leaves it out**; a range is cut to the part of the sheet that was kept. The validator answers
   `condition_unknown_cell`, `condition_range`, `condition_operand`, `condition_paint`, `conditions_need_named_rows`. This is a purely additive change to the schema; no version 1 or version 2 document without
   conditions changes, and the switch still decides whether any of it is stored.
5. **The choice of worksheet.** The report lists the visible worksheets (the name of the tab, which is the organization's text: shown, never logged; and the rows and columns the file claims, read from the
   `dimension` line without reading the worksheet). The request may name one by its number among the visible ones (`worksheet`, from zero); without it the first visible worksheet that holds anything is read. Hidden
   worksheets are skipped and counted. A template holds one sheet for now, so the others are counted with fixed words telling the author to upload each into its own template (SH7 changes that). A number that
   is not in the file is refused (`no_such_worksheet`) and nothing is stored.
6. **Check boxes** (ADR-0253 amendment, built as proved): a box is placed in the cell that holds the whole of it, else the cell that holds its centre, else the nearest cell of the table within three cells; otherwise
   it is reported as not placed. A Yes box with a No box in one cell, in one row up to four cells apart or in one column up to two rows apart is one yes/no cell; a lone box is a yes/no cell; the ticked state is
   not kept. **A box over a cell that holds text does not replace the text** and is reported as not placed (the owner's answer, 2026-11-13); **one exception found on the real plant form:** a cell whose text is only the words Yes or No is the caption of the box, so it becomes the yes/no cell (4 of the 20 box cells; the other 16 hold the question). **A box over a question keeps the question and the free cell just after it (and after its merged area) becomes the yes/no cell**, if that cell is inside the table, empty, not under a merge and not under a box; it is counted in its own note for the author to check, and a box with no such cell is reported as not placed. Other controls, shapes, embedded files (their cells are listed in
   the report), pictures (typed from the first bytes; a vector picture is never opened), external links, defined names, comments and pivot parts are counted and never run or followed.
7. **Links**: only `https` is kept, with a host and no user information, at most 2,000 characters, written with the scheme in lower case, on the cell that holds fixed text. A link inside the workbook and every
   other kind are counted ("17 links inside the workbook were not kept").
8. **With the switch off nothing changes**: the same reader reads, and the builder makes the version 1 grid it always made (four sizes, bold and italic, widths and heights with the version 1 limits, a fixed text up to
   1,000 characters). A text file is always made as it always was. Because the old reader is retired, "the present reader" for the switch-off path is the new reader under the old builder; the output is the same
   and a side-by-side run over the hostile corpus, the sample forms and the real workbooks showed it (see the sprint record).
9. **The dimension of the sheet decides what a file can give.** A sheet where the filler adds the rows (dimension 1) takes only the names of the columns, as before, and the report counts what could not come
   in (conditional colours, links, check boxes).
10. **Notes** (`ImportNote`) gain: hidden and other worksheets, vector pictures, pivot tables, worksheets with comments, defined names, embedded files, shapes, form controls that are not check boxes, links inside the
    workbook and links not kept, conditional colours left out, check boxes turned into yes/no cells and pairs, check boxes not placed, cells with a number format the sheet does not have, fonts the sheet does not have.
    Counts only; nothing a file holds is logged or audited (`SheetImportLogsAreCleanIT`).

## Consequences

- A new dependency set (13 jars, listed in the sprint record) in the dependency scan; the logger of the library is off; the first workbook after a start costs about a second of class loading (the owner accepted this and
  a warm-up is left out).
- `XlsxReader` is retired; the writer stays until SH3. The 57 hostile lines of the old corpus still hold, and ten new lines and the 42 cases of the extras proof are added.
- The format gains `conditions`; the web adapter carries them like any part it does not edit.
- A workbook with a formula of more than 4,000 characters or nested deeper than 32 cannot be uploaded until SH2 decides otherwise; the real files were measured (the sprint record gives the longest).

## Not decided here

Formulas as formulas (SH2), writing a file and the print view (SH3), drawing the look and the conditional colours (SH4), fill format 3 and the reviewer class (SH5), the author editor and setting a cell type
by hand (SH6), several sheets in a template (SH7), images in templates, reading the legacy binary format or a password-protected file (still refused).
