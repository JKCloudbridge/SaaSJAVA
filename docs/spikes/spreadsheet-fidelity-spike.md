# Fidelity and the fill-in model (POC 2)

- **Status:** development done 2026-11-12; waiting for the product owner's reading. Not product code. Nothing here is built for real until the owner has read this report and accepted the ADRs.
- **Branch:** `POC2` (from `POC1`; nothing committed).
- **Prototype:** `spikes/sheet-engine/` (Java) and `spikes/sheet-grid/` (browser). Disposable. No change under `platform-app/src`, `platform-web/src`, any `pom.xml` or `package.json` of the platform, no migration, no change to the API or its generated documents.
- **Builds on:** the [POC 1 report](spreadsheet-engine-spike.md) and ADR-0251, 0252, 0253 (Proposed). The POC 1 report is not edited (one pointer was added at its end).
- **Decisions this feeds:** [ADR-0251](../adr/0251-sheets-format-version-2-and-the-engine.md), [ADR-0252](../adr/0252-formulas-by-identifier-the-stored-form-the-whitelist-and-the-translators.md), [ADR-0253](../adr/0253-workbook-files-through-a-library-reading-by-stream-guards-first-and-what-it-reverses.md) (each with a new "Amendment" section) and the new [ADR-0254](../adr/0254-attachment-cells-a-list-of-file-identifiers-of-this-fill-in.md) (all Proposed).

## Summary in plain words

1. **Everything the two forms need can be stored in the format and read by the upload, and the recommendation of POC 1 (keep our own grid, extend our format, use the library on the server) is not reopened.** The work grows: about 12 to 14 sessions instead of 8 to 9 (section 3.3).
2. **Check boxes:** a safe reader of our own found all 36 and placed every one **exactly** in a cell (36 exact, 0 by nearest cell, 0 unplaced). 16 are a Yes box beside a No box in one cell and become **one yes/no cell each**; 4 stand alone. The library alone does not expose them. All 42 new hostile cases are refused or contained.
3. **The picture in the plant workbook is not a logo.** It is the 5.5 KB icon that the **18 embedded files** show. There is no logo in either workbook. The 17 links all point **inside** the workbook; none is a web address. 32 floating text boxes hold text that is not in a cell (decision for you, section 2.1).
4. **Per-cell types and attachment cells work as a format and as a validator** (prototype): 300 of 300 generated fill-ins accepted, 35 of 35 hostile ones refused with a stable code and no repeated text, the plant form with about 50 mixed cells (52) fits. **All 605 version 1 documents still keep their text and hash** against the revised version 2 draft.
5. **The four visible supplier tabs load into one version 2 document** (27 KB, valid, hash the same whatever the order of the keys; the three hidden tabs are skipped and counted). One fill-in covers all tabs.
6. **How exact is "exact":** with any colour as a checked `#rrggbb` value every fill and text colour is **identical** (distance 0); the closed palette of POC 1 is off in half the fills and in all text colours (distance above 10). Fonts: every family of both forms maps (plant 2 of 2 exact; supplier 5 exact and 1 by alias; 0 unmapped). Widths and heights are within 2 px **if the format allows narrower columns and shorter rows than version 1 does** (it must be widened). Drawn under the **strict style policy: 0 violations on 18 pages.**
7. **Formulas:** the set grows from 9 to **40** functions (43 in the proof's list with the three exception functions below). On 225 cases the browser evaluator equals the documented behaviour on all 223 it accepts and equals POI on 220. **POI itself deviates on 3 functions** (`STDEV`, `VAR`, `RANK`, on edge inputs) and cannot run `N`; by the threshold written first these are **not in the set** until you decide (question 1 below). The growing range token works in Java and in the browser.
8. **Not run:** the local object store did not answer (Docker's tools hang), so the file test through the record-files path was **not run**; the side-by-side comparison with the spreadsheet program is yours (pictures are in a folder outside the repository). Section 4.

## Questions that the numbers raise for the owner

1. **Statistics functions.** POI's own evaluator gives the wrong answer on edge inputs for three functions: `STDEV` and `VAR` of a single number (it says `#NUM!`, the documented answer is `#DIV/0!`), and `RANK` of a number that is not in the list (it says 1, documented `#N/A`). Our interpreter follows the documented behaviour. The threshold written first says a function needs POI and the browser to agree on every case, so **`STDEV`, `VAR`, `RANK` are out of the first set**, and `COUNT` is limited to cell references and ranges (a typed constant gives POI a wrong count). **Recommendation: accept a documented exception** (the three stay in, the test file keeps the three cases marked "POI deviates", the server's interpreter is the authority, as ADR-0252 already says). I did not grant myself that exception.
2. **The 32 floating text boxes of the plant form** hold text that is not in any cell (all sit over cells that already have text). The upload would drop them and count them. **Recommendation:** keep dropping and counting ("32 text boxes were not kept"); the author retypes what matters into the cell. Say if you want their text brought in as fixed text instead.
3. **Narrow columns and short rows.** Version 1 allows a column of at least 40 px and a row of at least 16 px. The supplier form has a 36 px column and rows of 7 px; the plant form a row of 10 px. **Recommendation:** version 2 allows 8 to 1000 px for a column and 4 to 600 px for a row (section 2.4).
4. **Fixed text up to 5,000 characters** (the supplier form has a note of 1,172; version 1 stops at 1,000). Recommendation: 5,000 for fixed text, 1,000 for an input cell (it was POC 1's question 1; the prototype used it).
5. **Tab names** are the organization's text. Recommendation: a tab has a title of at most 31 characters, no markup, shown as the tab's name; the proof drew "Tab 1" because it does not read names yet.

## 0. Answers given by the product owner (2026-11-12)

| Question | Answer |
|---|---|
| Hidden SPM tabs | Skipped and counted. |
| Fonts and colours | Any colour as a validated hex value; fonts from a larger closed list with fallbacks; a font name from a file is never used directly. |
| The logo (EMF) | The author re-uploads it as PNG or JPEG into a template's header; stored as a file; size limited. (Finding: there is no logo in the files; section 2.1.) |
| Hyperlinks | `https` only. (Finding: all 17 links are inside the workbook; section 2.1.) |
| Attachments | Up to 5 files per cell; a required flag per cell; the downloaded spreadsheet file shows "(n attachments)", never a file name. |
| Yes box next to a No box | One yes/no cell. |
| Embedded files (18) | Skipped and counted. |
| Real filled example | Not answered (none yet; counts only will be recorded when one arrives). |
| "How does the spreadsheet program do check boxes? Univer has it too" | Answered in section 2.1: they are old-style **form controls** drawn over the cells, each with a place and a ticked state; the platform stores a yes/no **cell** and draws a check box in it. Univer's check box is also a cell kind (a validation rule of kind "check box"), which fits the same design; it was not needed. |

## 1. Pass thresholds, written before any number was seen

Written 2026-11-12 before the first measurement. They may be tightened, never loosened (ADR-0082). The prompt's numbers are the starting values; rows marked *(added)* are mine.

| WP | Pass condition |
|---|---|
| **1 Extras** | (a) Every one of the 36 check boxes is placed in a cell of the table: exactly (the anchor lies inside one cell) or by a stated nearest-cell rule; **0 unplaced**; the report gives the split exact / nearest / none. (b) Every Yes box beside a No box is recognised as one question; the report gives the count of pairs and of boxes left alone. (c) Every link that is not `https`, or longer than 2,000 characters, is refused; every kept link is shown as text. (d) Every new hostile case (a huge number of shapes, a control anchored far away, a script-scheme link, a very long link, an image with a wrong type, a bomb inside a drawing part, a doctype in a drawing part, a part that reaches outside the archive) is refused or contained inside the limits, and none ends in a crash, stack overflow, memory error or time out. (e) No class of the scratchpad module is loaded. (f) The owner's file is never opened for writing (reads from bytes). (g) *(added)* Nothing read from a drawing part (text, name, link, file name, image bytes) is printed in any log or output; counts only. |
| **2 Per-cell types** | (a) All 605 version 1 documents keep their canonical text and hash through the revised shim (605 of 605), and an archived copy keeps its hash. (b) The prototype validator accepts every good generated fill-in and refuses every hostile one with a **stable code and no repeated text**. (c) The attachment lifecycle table has no blank cell. (d) The list of classes that must change is complete, found by search of the code. (e) *(added)* A version 2 document with cell types is refused by the unchanged version 1 validator. |
| **3 Several sheets** | (a) The four visible SPM tabs load into one document that validates. (b) A version 2 document with one sheet reads from and writes back to version 1 with identical bytes and hash (605 of 605). (c) A four-sheet document has a stable canonical text and hash that does not depend on key order. (d) Every seam has a written answer, a size (S/M/L) and an order. (e) *(added)* The three hidden tabs are skipped and counted, never read into the document. |
| **4 The look** | (a) Option (c) (any validated hex colour, font from a larger closed list): **every distinct fill within a colour distance of 10**, every column width and row height **within 2 pixels** of the file. (b) Every font family of the two files maps to a list entry or a stated fallback (0 unmapped). (c) Nothing from a file reaches a style value unchecked (a colour is `#rrggbb` after a check; a font is a list entry). (d) 0 violations under the strict content policy for the forms drawn. (e) *(added)* Distances and counts are reported for options (a), (b) and (c) side by side. The colour distance is the CIE76 distance in Lab space (stated here so it cannot change later). |
| **5 Formulas** | (a) Every function in the final set has cases (blank, text, number as text, errors, an empty range, a range with text) on which POI's evaluator and the browser evaluator agree **100 percent**, or the function is put out of the set with the reason. (b) The growing-range token round-trips (to identifiers and back) and recalculates correctly when a row is added. (c) The hostile formula corpus (the 21 cases of POC 1 plus cases for each new function and for the new token) is refused 100 percent. (d) *(added)* Every function the evaluator lists is either supported by POI or documented as outside the set. |
| **6 Matrix** | No blank row; every row marked proved, reasoned or needs a change, with a size; every "proved" names the run that proved it. |
| **7 Decision** | The POC 1 recommendation (Option A) is not reopened unless a number forces it; if it does, say so plainly. The backlog is re-sized with the sessions that grow. |

Two things I fixed or added **after** seeing numbers, said openly: (i) the 72-name palette and the 16-entry font list were written down (in `lib/poc2Look.mjs`) before any distance was computed, not tuned to the data; (ii) for the widths and heights (threshold 4a) the measurement showed that version 1's own limits (40 px, 16 px) break the 2 px rule, so the **specification** of option (c) includes widened limits for version 2; both results (with version 1 limits and with the widened ones) are reported.

## 2. Results

### 2.1 WP1. The parts the upload skips today (the plant form's extras)

Code: `spikes/sheet-engine/src/main/java/spike/Extras.java` (the reader), `ExtrasHostile.java` (the cases), `ExtrasExport.java` (positions for the pictures). The reader works on **bytes only**, after the same archive pre-scan as POC 1 (duplicate and `..` part names, macros, sizes counted while inflating). Every XML part is read with a streaming reader that has the document type declaration switched off and nothing resolved (a document type is refused). The old-style drawing part is not well-formed XML, so a bounded text scanner reads it (no entity expanded, nothing run). Nothing read from a drawing (a name, a caption, a link, a picture) is kept: counts, cell positions and stable keys only.

**What the plant workbook holds (counts):** 36 check-box controls (2 ticked), 18 embedded objects (1,990,656 bytes together, shown as icons), 82 plain rectangles in the drawing layer (32 with text, all starting inside the table, none on an empty cell), 54 shapes in the old-style drawing (36 check boxes and 18 embedded-object icons), 17 links, one 5,536-byte picture (a vector metafile, header size 64 by 46 device units). The supplier workbook has none of these (it has 5 external-link parts and a pivot table).

**How the spreadsheet program does check boxes.** A check box is an old-style *form control*: a shape drawn over the cells, with an anchor (from a cell and offset to a cell and offset), a caption (the words "Yes" or "No" in the plant form) and a state (ticked or not), optionally linked to a cell. The sheet part lists the controls and their anchor; a separate drawing part repeats the shapes; a small part per control holds the type and the ticked state. The library's streaming reader does not expose any of this (it reads cells, styles, text). So the design is **our own small reader**, written above.

| Check | Result | Threshold |
|---|---|---|
| Check boxes found / ticked | 36 / 2 | |
| Placed in a cell of the table | **36 exactly (the whole box lies in one cell), 0 by the centre rule, 0 by the nearest-cell rule, 0 unplaced** | 1a pass |
| Captions: found by the shape number / by position / not found | 23 / 13 / 0. (13 boxes have a different shape number in the sheet and in the old-style drawing; they are paired with the drawing's shapes that no number claimed, in the same cell, in the order of their left edge, and only when the counts in that cell are equal.) | |
| Yes box beside a No box recognised as one question | **16 pairs**, each pair in one cell (both boxes stand side by side in the same cell). Rule: a Yes box and a No box in the same cell, or in one row up to 4 cells apart, or in one column up to 2 rows apart, each used once. | 1b pass |
| Boxes left alone | **4**: two rows of two neighbouring cells with **no caption**; they are single yes/no cells (not paired, because nothing says they belong together) | |
| Value of a pair | Yes ticked gives true, No ticked gives false, neither gives empty, both is refused. In the file one Yes and one No are ticked. | |
| Links | 17 found; **0 are `https`**; **17 point to a place inside the workbook** (2 to the other worksheet, 15 to a name or place that is not a worksheet); 0 refused as a bad scheme, 0 too long, 0 malformed | 1c pass |
| Pictures | one, a **vector metafile of 5.5 KB used as the icon of the 18 embedded objects**; no picture in the drawing layer | |
| Embedded objects | 18 found with their cell: 16 stand in the same rows as the 16 Yes/No pairs (the cell to the right of the pair), 2 elsewhere | |
| Time on the plant workbook / the supplier workbook | 220 to 277 ms / 45 ms (cold JVM) | |
| Scratchpad-module jars on the class path / classes loaded | 0 / 0 | 1e pass |
| The owner's files | read from copies by bytes; the originals in `Downloads` have the same size, date and SHA-256 as at the start | 1f pass |

**Hostile cases** (`ExtrasHostile`, 42 cases, each a *valid* package with one hostile part): 1,001 controls; a control anchored at column 16,000 and row 1,000,000 (contained: not placed, 377 ms); negative, text, reversed and huge-offset anchors (contained: refused as controls); links with `javascript:`, `data:`, `file:`, `mailto:`, `http:`, `ftp:`, `vbscript:` and `https` with a user name (all refused as links); an `https` link in capitals (accepted); a link of 3,000 characters, a link with a space or a back slash (refused/contained); a control character in a link (not XML: the file is refused); 5,001 links; 5,001 shapes (refused), 5,000 (accepted); a document type with an entity bomb or an external entity in a drawing part and in a control part (refused); a drawing part that inflates to 30 MB (refused at 424 ms); a drawing nested 20,000 deep (refused by the reader's own depth limit); not XML; a relationship that leaves the archive and an absolute one (refused / not followed); a picture part whose bytes are a program or markup (typed as "other", never used); a part name repeated but for the case (refused); an old-style drawing with 5,001 shapes or 5 MB (refused) and with entity text and unclosed tags (contained, nothing expanded). **Result: 42 of 42 refused or contained; no crash, stack overflow, memory error or time out (the slowest 424 ms) under a 300 MB heap.** (1d pass, 1g pass: no output holds any text of a file.)

**The picture (WP1.2).** *It is not a logo* and is the icon of the embedded files. A safe reader can say its type (from its first bytes: PNG, JPEG, a vector metafile, other) and, for the metafile, its header size; it never decodes it. How a template could hold a real image (the decision is yours; the owner's answer was "re-upload as PNG or JPEG into a template's header, stored as a file, size limited"):

| Option | What it is | Cost | Risk |
|---|---|---|---|
| **A. No images in templates** | the reader notes "1 picture was not kept" | none | a logo cannot be on the form |
| **B. One header image per sheet, PNG or JPEG** (recommended when wanted) | stored as a file in the object store, referenced from the sheet by identifier (never inline in the grid); type from the first bytes, size from the header bytes, at most 256 KB and 1,600 by 400 px; shown by the screen, the print view and the downloaded file (the library writes PNG and JPEG); never inside a fill-in | M (about one session) plus an ADR change: ADR-0120 serves every file as an attachment and refuses inline display, so two image types need a narrow exception (nosniff, the sniffed type only) | a hostile image that is a program or a page: refused by the first-bytes check and served only with its own type; a picture bomb: the size is read from the header, never decoded on the server |
| C. SVG | after a sanitiser | L, ongoing | an SVG can carry script; ADR-0120 refuses it today; **not recommended** |
| D. The metafile as it is | | would need the scratchpad module, which ADR-0253 forbids | **refused**; ask for PNG or JPEG |

**Links (WP1.3).** Where stored: a **cell property** (`link`, next to the value, an `https` address of at most 2,000 characters with a host and no user information), never the cell's value. Shown as the cell's text, underlined; opened in a new window with `noopener noreferrer`; never run. The 17 real links are all **inside the workbook** (a jump to a place), so under the rule all 17 are **dropped and counted** ("17 links inside the workbook were not kept"); their text stays. The plant form's "Action plan" links cannot work in the platform anyway: Plant activity and Action plan are two records.

**Other parts (WP1.4).** Form controls other than check boxes, embedded objects, external links, defined names, comments, the pivot parts: skipped and counted, never run or followed. Notes read: "36 check boxes were turned into yes/no cells (16 pairs, 4 single)", "18 embedded files were not kept (they stood in these cells)", "82 drawing shapes were not kept (32 held text)", "17 links inside the workbook were not kept". A drawing shape's text is **not** brought in (question 2 above).

**Attach prompts (WP1.5).** The reader never guesses from text (34 cells of the plant form mention "attach", in 7 columns, which is not a rule). The rule an author applies: **select the cell or merged area and set its cell type to "Attachment"** (the same menu as a list or a date), one action for a block of cells. To save the author work, the upload report **names the cells where an embedded file stood** (18 positions, exact: 16 beside the Yes/No cells, 2 elsewhere); the editor marks them with a dotted outline until the author sets a type or dismisses the mark. That is deterministic (a position in the file), not a guess from words. Tested on the real file: the 18 positions are found and drawn (the dotted cells in the plant picture); **applying the type by hand was not tested in a browser editor** (the editor is not built).

### 2.2 WP2. Per-cell types and the fill-in model

Code (harness, platform classes untouched): `spikes/sheet-engine/src/harness/java/app/platform/sheets/internal/PocV2Doc.java` (format version 2 draft), `PocFill3.java` (the fill-in validator for it), `PocV2Shim.java` (the 605 again). Every sheet body of version 2 goes through the **real** `SheetGridValidator` unchanged, so each sheet keeps every rule of version 1; the new class adds only what version 2 adds.

**The draft of format version 2 (additions over POC 1).** `{"formatVersion":2,"sheets":[{"key","title","fillable", ...the version 1 body..., "freeze":{rows,cols}, "print":{paper,orientation,fitToWidth,repeatHeaderRows}, "inputs":{"rowId:columnId":{"type","required","class","picklist","maxFiles"}}}]}`. A cell's own `type` (text, number, date, picklist, boolean, **attachment**) overrides its column's; `required` per cell; `class` is `filler` (default) or `reviewer` (the class is a field in the format; its server enforcement is a later sprint; the prototype validator already refuses a filler writing a reviewer cell and the reverse, as a trial); `maxFiles` 1 to 5 for an attachment cell (default 5). A merged input area is one cell (the top left, as now). Rules checked: the cell exists, is an input cell of a sheet with named rows, the type is in the list, a list has 1 to 100 distinct values, a file limit only on an attachment cell. A column with the type "attachment" is not needed: the SPM data sheet and the plant form need cells.

**The fill-in for it ("fill format 3").** `{"formatVersion":3,"sheets":[{"key","rows":[{"id","cells":{columnId: text | true | false | ["fileId", ...]}}]}]}`. Fill formats 1 and 2 (values and closed styles) are **not touched**: a fill pinned to a version 1 template keeps its reader and writer; only a fill pinned to a version 2 template is format 3. Stages (the first with a problem answers alone, as `FillValidator`): size before parsing, nesting, JSON with a repeated key refused, shape, limits (sheets, rows, text length, files per cell), text (no markup or control characters; a file identifier must look like an identifier), sheets and rows (the sheet exists in the pinned version, once, is **fillable**, the row rules of today), cells (the column exists, **the cell is an input cell**, the class against the person's role, the effective type, the value against the type; for an attachment cell **only that each identifier is one of this fill-in's own files**, no duplicate, at most the cell's limit). Required cells and required attachments are asked at Submit, not at save.

| Check (prototype run) | Result | Threshold |
|---|---|---|
| Version 1 documents: canonical text and hash through the version 1 path / read as version 2 with one sheet and accepted by the new validator / **written back as version 1: same text, same SHA-256** / archived copy keeps its hash | **605 / 605 / 605 and 605 / 605** (600 generated templates of both dimensions, the two minimal documents, the platform's sample form twice, the plant workbook through the upload) | 2a pass |
| A version 2 document, and one with typed cells, offered to the unchanged version 1 validator | refused both | 2e pass |
| The plant-like form: sheets / cells with their own type or class | 1 / **52**: 20 yes/no (16 pairs and 4 single), 18 attachment, 4 number, 1 date, 1 list, 8 reviewer cells | "about 50 mixed" |
| Good generated fill-ins (plant-like, one-dimensional data sheet, three-sheet template) | **300 of 300 accepted**; same canonical text and hash when every object's keys are reversed: 300 of 300 | 2b pass |
| The plant form filled completely: accepted / still missing at Submit; the same after file 1 was removed | yes / 0; **4** (the four required attachment cells whose file is gone) | |
| Hostile fill-ins (35): a yes/no given text or a number; an attachment given text, yes, a list inside a list, six files, two files over a cell's own limit of two, the same file twice, a file of another fill-in, an identifier that is a path; a number given letters; an impossible date; a list value outside the list; a fixed cell; an unknown column; an unknown or repeated sheet; a second sheet the template lacks; a cell in an information tab; a row of another sheet's column; a new row on a fixed sheet; the same row twice; a row named like a column; markup; 1,001 characters; a reviewer's cell by the filler and the reverse; a repeated key; not JSON; the wrong format; an extra property; nesting; a document over 1 MB; nine sheets | **35 of 35 refused with the expected stable code; answers that repeated the typed marker text: 0** | 2b pass |

**The attachment lifecycle (WP2.3).** A cell holds **a list of file identifiers** (up to 5; the cell may lower it). The files are the files of the **fill-in's header record** (ADR-0120, ADR-0193 section 1), in the platform's object store; **bytes stream through the application, no signed address, no address in the cell** (ADR-0120). Full table in ADR-0254; short form:

| Moment | Add or remove a file | The cell | Required attachment cell |
|---|---|---|---|
| Draft / returned | the person who started it (`FillFileRules`, unchanged) | the same save that names the file; removing a file removes its identifier from every cell **in the same transaction** | not asked at save |
| Submit | none | | **at least one file that still exists** (asked against the files now on the header) |
| Sent / approved | only a person holding the override ability, a counted and audited correction (unchanged) | the correction removes the identifier through the same counted door; it **cannot leave a required attachment cell empty** (refused in fixed words) | already satisfied |
| Archived | nobody | read only; the archive keeps the identifiers, the files stay on the header (kept for good) | |
| Report | reportable columns hold **text, number, date, yes/no or a pick**; an attachment cell is **not reportable** (present or not is a later, separate choice) | | |
| Spreadsheet-file download | the cell shows **"(n attachments)"**, never a file name; an empty cell stays empty | | |
| Register page, held copy | no attachment cells: the register is a generated sheet; a held copy is a template draft (templates hold no values) | | |

**The classes that must change (found by search of the code, WP2.4).** `platform-app/src/main/java/app/platform/`:
`sheets/internal/` — format: `SheetGrid` (model), `SheetGridReader`, `SheetGridValidator` (a version 2 branch), `SheetGridNormalizer`, `SheetGridWriter`, `SheetSchemas` and `platform-api-contract/src/main/resources/schemas/sheet-grid.json` (254 lines); fill-in: `FillDoc`, `FillStructure` (the effective type per cell, the cell classes), `FillValidator` (format 3), `CellValues` (the `attachment` type), `FillValues` and `FillValueStore` (reportable values: an attachment is not one), `FillChanges`, `FillOwners`, `SheetFills`, `SheetFillStore`, `DefaultFillLifecycle`, `DefaultFillReports`, `DefaultFillSubmission`, `FillArchive`, `FillArchiveJob`, `SheetFillController`; file side: `XlsxWriter`, `SpreadsheetImporter`, `SheetImportBuilder`, `RawSheet`, `DefaultSheetExports`, `DefaultSheetPages`; the version table: `SheetVersions`, `SheetVersionStore`, `DefaultSheetLifecycle`, `BlankGrids`, `SheetChanges`. `inspection/internal/`: `FillFileRules` (the add and remove rule stays; a removal must also clear the identifier), `FillApprovalHooks` (Submit asks the required attachments), `FillReport`, `RegisterLayout`. `data/internal/`: `FileService` and `FilePolicy` (a removal calls the new seam below). `sharedkernel/files/`: `RecordFileRules` (the new "after a removal" callback). Migrations: **V091** (the function of V064 that fixes the shape of the first version compares per sheet key; `sheet_fill_value.sheet_key`). Web (`platform-web/src/`): `lib/sheets/fillModel.ts`, `fillTypes.ts`, `fillApi.ts`, `types.ts`, `adapter.ts`, `gridModel.ts`; `components/sheets/FillForm.tsx`, `FillWorkspace.tsx`, `SheetGrid.tsx`, `useFillEditor.ts`, `GridView.tsx`, `SheetWorkspace.tsx`, `SheetPrintView.tsx`, `UploadSheet.tsx`; the cluster's `solutions/inspection/` pages that start and list fill-ins. 10 backend test files and the isolation harness name these classes. (The search lists files that name the classes; it is complete for names, not a proof that nothing else changes.)

**WP2.5 (the local object store).** **Not run.** Ports 8333, 5432 and 6379 accept a connection, but the store did not answer an S3 request (the bucket could not be made through the platform's own `S3ObjectStore`; a plain request to its status address timed out), and the `docker` command itself did not answer within two minutes. The application was not running. So neither the store nor the record-files path of a fill-in header was exercised. Probe: `spikes/sheet-engine/src/harness/java/app/platform/data/internal/PocStoreProbe.java`.

### 2.3 WP3. The supplier workbook as one template with four tabs

Code: `PocSpmTabs.java`. The four visible worksheets (the instruction tab, the overall tab, the data tab with the frozen header band and the 22 formulas, and the last tab) become four sheets of one version 2 document (named rows, fixed text, merges, widths and heights, freeze, a print set-up of A4 landscape fitted to the width; formulas come in as their last value in this load, the stored form is WP5). The three hidden worksheets are skipped and counted.

| Tab | Rows × columns | Cells with text | Merges | Kind |
|---|---|---|---|---|
| 1 | 65 × 12 | 88 | 54 | information |
| 2 | 37 × 6 | 7 | 1 | information |
| 3 (data) | 9 × 59 | 123 | 16 | fillable |
| 4 | 8 × 28 | 9 | 1 | information |

| Check | Result | Threshold |
|---|---|---|
| Loaded into one document that validates | **accepted, 27,057 bytes** canonical (limit 1,000,000 for the template); **3 hidden tabs skipped and counted** | 3a, 3e pass |
| The 1,172-character note that makes today's upload refuse the file | accepted (fixed text allowed to 5,000 in the draft) | |
| Canonical text and hash when the keys are written in reverse order; the canonical text as a fixed point | **same; same** | 3c pass |
| The order of the tabs | **part of the hash** (reordering tabs changes it: the tab order is content) | |
| A one-sheet document equals version 1 byte for byte | **605 of 605** (section 2.2) | 3b pass |
| Nine sheets; the same sheet key twice; the same column identifiers in two sheets | refused (`too_many_sheets`); refused (`duplicate_sheet_key`); **accepted** (identifiers are unique within a sheet) | |
| One fill-in over all tabs: 29 cells of the data tab; a cell on an information tab | accepted (495 bytes canonical); refused (`sheet_not_fillable`) | |
| The data tab's input cells | 148 (the bordered cells with no text) | |

**The seams, with the sizes of POC 1's WP7 confirmed or corrected by the real file** (S a day or two, M a few days, L about a session; order = the order of work):

| # | Seam | Answer | Size |
|---|---|---|---|
| 1 | Version table and its guards (`dimension`, `rows_mode`, `grid_bytes`) | The unit is the **template**; the three partial unique indexes, the moves and the immutability trigger are about the version, not the grid. The three columns describe the first sheet (or become `sheet_count`); the V064 function that fixes the shape of the first version compares **per sheet key** (V091, expand only). Confirmed: a four-tab template is 27 KB. | S–M |
| 2 | Validator and limits | rows and columns are per sheet (the real data tab is 59 columns of the allowed 100); bytes per template (27 KB of 1 MB); sheets at most 8; the draft already runs the real per-sheet validator. Corrected: fixed text 5,000; width 8 to 1000, height 4 to 600 (section 2.4) | M |
| 3 | `FillDoc` and `sheet_fill` | one fill-in covers all tabs (fill format 3, a `sheets` list); `row_count` is the sum; archive unchanged | M |
| 4 | Reportable columns (`sheet_fill_value`) | a `sheet_key` column (default `main`) and the indexes widened (V091) | S |
| 5 | Archive | unchanged (one document) | none |
| 6 | Footer stamp | one footer row per worksheet in the file, on screen per tab, in print per page | S |
| 7 | Download | one workbook with N worksheets (the library does it) | S |
| 8 | Change requests and the register | the register page is its own generated sheet; a place in a request gains an optional sheet key | S |
| 9 | Approval and publish | act on a version: all tabs move together | none |
| 10 | Upload | reads **every visible worksheet**, one sheet each, hidden skipped and counted; the **choice of worksheet** is needed only when the target is a **single-sheet** record (the plant workbook has 2 visible worksheets: the dialog lists them with their row and column counts, the author picks one) | M |
| 11 | Formulas | the token reserves a fourth part, the sheet key; another sheet is refused until the key is written | S |
| 12 | The screen | a tab bar (a proof of it ran: 4 tabs, switching, each with its own freeze) | S |

### 2.4 WP4. How exact is "exact" (the look)

Code: `lib/poc2Look.mjs` (the sets, written first), `poc2-look.mjs` (the measure), `poc2-look-shots.mjs` (the drawing under the strict policy), `components/Poc2OwnGrid.jsx` (the page), `scripts/poc2-gen-css.mjs` (the classes). **Correction to POC 1:** its closed palette is **32 names** in the code (5 neutrals and 9 hues in 3 strengths), not 36 as the report says; POC 1's figure "12 of 17 within 30" came from a separate 36-entry hex list in its measuring script and the distance in RGB. Here the grid's real list is measured, with the colour distance fixed in section 1.

Real colours: 18 distinct fills (6 plant, 12 supplier, counting each workbook's visible tabs and the conditional colours) and 5 distinct text colours (2 + 3). Within a colour distance of 10 (CIE76):

| | (a) POC 1 closed lists (32 names) | (b) wider closed lists (72 names) | **(c) any `#rrggbb`, checked** |
|---|---|---|---|
| Fills, plant (6) | 4 of 6 (all within 30; worst 29.8) | 4 of 6 (worst 29.7) | **6 of 6 (0.0)** |
| Fills, supplier (12) | 6 of 12 (worst 29.8) | 8 of 12 (worst 30.9) | **12 of 12 (0.0)** |
| Text colours, plant (2) | **0 of 2** (worst 95.6) | 1 of 2 (worst 42.9) | **2 of 2** |
| Text colours, supplier (3) | **0 of 3** (worst 32.3) | 1 of 3 (worst 13.2) | **3 of 3** |

A wider closed palette barely helps (the real forms use colours of their own, not a regular grid), so option (c) is the only one that meets "every distinct fill within 10": **pass 4a for colours with (c)**. Fonts and sizes (the same for (a)–(c) except the family list): the plant form uses 2 families and the supplier form 6; with the 16-entry list (13 named, 3 generic) **plant: 2 of 2 exact; supplier: 5 exact and 1 mapped by alias to the nearest entry; 0 use a generic fallback, 0 are unmapped**; the 4-name list of option (a) holds all of them too but only as 2 or 3 classes (the heavy display family and the others collapse). Sizes: 10 distinct per workbook, **all whole points inside 8 to 28** (rounding error 0).

| Widths and heights (the file's own pixels: a column's stored width times 7, a row's points times 96/72) | plant | supplier |
|---|---|---|
| Columns / narrowest / widest | 22 / 65 / 437 px | 105 / **36** / 453 px |
| Columns more than 2 px off with **version 1 limits** (40 to 1000 px) | 0 | **1** (worst 4 px) |
| Rows with a stated height / smallest / tallest | 58 / **10** / 160 px | 83 / **7** / 319 px |
| Rows more than 2 px off with **version 1 limits** (16 to 400 px) | **1** (6 px) | **4** (worst 9 px) |
| With the draft's limits (columns 8 to 1000 px, rows 4 to 600 px) | **0 and 0** | **0 and 0** |

So **4a is met with option (c) only if version 2 widens the limits** (said openly in section 1). A caution on the proof's own data: the exporter of POC 1 stored `round(width × 7 + 5)`, counting the padding twice; the pictures and these numbers use the corrected pixels (5 fewer per column). The POC 1 screenshots were therefore 5 px too wide per column; none of POC 1's conclusions depended on that.

**How a free colour reaches the page without breaking the strict policy.** The server stores and checks `#rrggbb` (a closed shape: `^#[0-9a-fA-F]{6}$`); the page checks it again, and hands it to the cell as a **CSS custom property set through the style object model** (`element.style.setProperty`), used by two classes (`background-color: var(--pc2-bg)`, `color: var(--pc2-tx)`). No style attribute is written into the markup and no style element exists. A value that is not that exact shape is dropped. Text with a fill but no text colour gets a dark or light ink chosen by the fill's brightness. The fonts are classes (the stacks are written only in the generated stylesheet). (d) **A font name from a file as typed is rejected, as the prompt says:** it would put an attacker's text into a style value, and a font the viewer lacks changes the layout silently; the name is looked up in a table or falls back to a generic key, and the *key* is stored.

| Check (every tab of both forms, three looks, strict policy `style-src 'self'`) | Result | Threshold |
|---|---|---|
| Pages drawn | **18** (plant 2 tabs, supplier 4 tabs, looks a, b, c) plus one in dark mode | |
| Violations of the policy | **0 on every page; 0 in dark mode** | 4d pass |
| Cells that carry a checked hex value (look c); computed background different from the file's | 244 / 12 / 25 / 1 / 111 / 11 across the six tabs; **0 different** | 4c pass |
| Nothing from a file reaches a style value unchecked | the colour only after the exact-shape check; the font only as a list key; sizes only as one of 21 classes; widths and heights as numbers clamped to the limits | 4c pass |

**Pictures for you to judge:** 19 pictures, one per tab and look, in `work/side-by-side` inside this session's scratchpad folder (outside the repository), with a checklist in the same folder (`README.md`) and in `Manual tests/POC 2 manual testing.md`. They show your text. I looked at one (plant tab 1, look c) myself: colours, merged areas, borders, row heights, the Yes/No cells with their ticks and the dotted attach cells came out as the form looks.

**What cannot be exact in any web grid, and what we can promise.** The same *look* and the same *behaviour*, not the same pixels: (1) a font the viewer's computer does not have is replaced by the next one in the stack, so text may wrap differently (the list limits this by naming the nearest families; **the platform could ship its own fonts for the list to remove the difference; not measured**); (2) the program's own sub-pixel text layout and row auto-height cannot be reproduced; a stored height is used as it is; (3) printing: the browser's page breaks differ from the program's (fit to width is stored; the break points are not); (4) zoom: the file's zoom is not applied. **Promise:** every colour, border, merge, width, height, font kind, size, number format and conditional colour as the file states them, to within 2 px.

### 2.5 WP5. More formulas

Code: `FunctionSupport.java`, `FormulaCases.java` (POI), `lib/poc2Formula.mjs` (the extended browser evaluator; POC 1's file stays), `lib/poc2-formula-cases.txt` (225 cases, written with the documented behaviour before any engine ran), `lib/poc2Formula.test.mjs`, `Whitelist2.java`, `GateCheck2.java`, `IdFormulas2.java`, `GrowingRangeCheck.java`.

**5.1 What POI 6.0.0 lists** (its own lists): of the 44 proposed functions **43 are supported**. **`N` is not supported** (so it is out of the set). `IFS` and `SWITCH` **are** supported. Kept out for ever (the evaluator lists these as supported or not, but they stay refused by our gate): `INDIRECT`, `TODAY`, `NOW`, `RAND`, `RANDBETWEEN`, `OFFSET`, `HYPERLINK` (POI supports them), `WEBSERVICE`, `FILTERXML`, `ENCODEURL`, `EVALUATE`, `CALL`, `INFO`, `CELL`.

**5.2 The cases.** 225 cases over every function (a blank, a text, a number as text, an error, an empty range, a range with text, the documented edge answers):

| Check | Result | Threshold |
|---|---|---|
| POI's evaluator equals the documented value | **220 of 225**; the 5 are `STDEV(5)` and `VAR(one value)` (POI `#NUM!`, documented `#DIV/0!`), `COUNT(1,"a",TRUE)` (POI 1, documented 2), `COUNT("3")` (POI 0, documented 1), `RANK(9, list without 9)` (POI 1, documented `#N/A`) | |
| The browser evaluator equals the documented value | **223 of 223 it accepts** (2 refused by the narrowed form of `COUNT`) | |
| **Documented = POI = browser** | **220 of 225** | 5a: see below |
| The 29 real formulas through the extended evaluator | 29 of 29 equal to the value the spreadsheet program stored | |
| POC 1's own tests (34 small cases, 29 real) | still pass | |

**Result by the threshold written first (5a):** a function stays in only if POI and the browser agree on every case. **Out of the set:** `N` (POI cannot run it), and `STDEV`, `VAR`, `RANK` (POI deviates on an edge input). `COUNT` stays **narrowed to cell references and ranges** (then it agrees on every case). **That leaves 40 of the 44 proposed functions** (`COUNT` narrowed). I did not loosen the rule; the choice to accept a documented exception for the three is yours (question 1).

The functions in the set: `ABS MOD INT ROUND ROUNDUP ROUNDDOWN SQRT POWER PRODUCT`, `IF IFERROR AND OR NOT ISNUMBER ISTEXT ISBLANK SUMIF COUNTIF AVERAGEIF IFS SWITCH`, `SUM AVERAGE MEDIAN MODE COUNT COUNTA MIN MAX LARGE SMALL`, `VALUE TEXT CONCATENATE LEFT RIGHT LEN UPPER TRIM`. `TEXT` takes only a listed format as a text constant (`0`, `0.0`, `0.00`, `0.000`, `0%`, `0.0%`, `0.00%`, `#,##0`, `#,##0.00`). The browser evaluator and the gate in the proof hold the full 43 (with the three exception functions) so that the exception can be switched on; the **hostile and good gate cases below run with that list**.

**5.3 The growing range.** Stored token `@{COL,*}` = **the column over every body row of the sheet, first to last, however many rows the filler adds**; `@{COL,ROW}:@{COL,*}` and `@{COL,*}:@{COL,ROW}` are ranges with an open end (written by the editor's own gesture). The translator writes the token **only for a range over exactly every body row**; every other range keeps its two row identifiers (no guessing). A row flagged as a **total row is not a body row**, so a total is not part of its own range; **a formula in a body row may not name its own column with the token** (it would be part of its own range: refused `circular`); a reference outside the sheet is still refused.

| Check | Result | Threshold |
|---|---|---|
| `AVERAGE(B2:B4)` over 3 body rows stored as | `AVERAGE(@{val,*})` | |
| Drawn with 3 body rows / after the filler adds two (the stored text unchanged) | `AVERAGE(B2:B4)` / `AVERAGE(B2:B6)` | |
| Value after adding two rows (2, 4, 9, then 10, 15): browser / POI recalculating the drawn text | 5 → 8 / 5 → 8 (equal) | |
| Round trip to the same stored text at 3 and at 5 rows | yes (Java and browser) | 5b pass |
| A partial range; an open end drawn at 3 and 5 rows; a total row's own column; the body column of another column | as designed (Java 12 of 12 checks, the browser test all) | |
| A reference outside the sheet | refused (`row_outside_sheet`) | |

**5.4 The gate.** `Whitelist2` is POC 1's gate (bounds on the text first, the library's parser, an allowlist of token kinds and of functions) with the larger set. One detail found: the library's parser files the later functions (`IFERROR`, `AVERAGEIF`, `IFS`, `SWITCH`) as a name followed by a call; the gate accepts that pair **only** when the name is an allowed function with no sheet, and refuses every other name. TEXT and COUNT carry their narrowed forms.

| Check | Result | Threshold |
|---|---|---|
| The 21 hostile formulas of POC 1 | **20 refused, and 1 (`TEXT(A1,"0")`) is now a permitted form by this decision**: counted, not hidden | 5c pass (see note) |
| New hostile cases (a hidden function inside a new one, a typed format, a name used as a variable, another sheet, 33 levels, a 2,000-term sum, `IFS` as a name, …) | **32 of 32 refused** | 5c pass |
| Good formulas (every function in a plausible use) | **45 of 45 accepted** | |
| The browser gate on 38 hostile formulas | 38 of 38 refused | |

Note on 5c: the corpus is refused 100 percent **except the one case that the decision itself moved into the allowed set**.

### 2.6 WP6. Everything still works (the matrix, again)

Marks: **proved** names the run; **reasoned** is paper with the reason; **needs a change** names the change and its size (S a day or two, M a few days, L about a session). No platform test was run (no product code changed). Rows 1 to 12 are POC 1's section 5.2, re-read against this draft; rows 13 to 20 are new.

| # | Area | Meets it? | Seam (class) | Mark |
|---|---|---|---|---|
| 1 | Template lifecycle (draft to retired, V064 guards, canonical text, hash, review edit) | the version table does not read the grid; a one-sheet version 2 is version 1 bytes | `SheetVersions`, `SheetGridValidator`, V064 | **proved** for text and hash (`PocV2Shim`: 605/605); guards **reasoned**; **needs a change (L)** version 2 branch |
| 2 | Approval process, hooks, record lock | none reads a cell (code search) | hook implementations | **reasoned**; none |
| 3 | Numbering | fields of the record | `TemplateApprovalHooks`, `NumberSeries` | **reasoned**; none |
| 4 | Publish, unpublish, retire, options, audience | records and shares; the sheet use is the download | `TemplatePublishing`, `TemplateDownloads` | **reasoned**; download (row 6) |
| 5 | Footer stamp | made at show time; **one per tab** | `TemplateSheetTexts.footer`, `revision.ts`, writer | **reasoned**; **S** |
| 6 | Download and print | the library writes N worksheets; page set-up per sheet from one source | `XlsxWriter`, `DefaultSheetExports`, `SheetPages` | **proved** that the library writes and rereads a sample (POC 1: 15/15; the file opening in the program is the owner's step); **needs a change (M)** |
| 7 | **Change requests** (register, held copy, Revisions, open-request counts, list, approval chain) | one sheet (the common case): nothing in the chain reads the grid; several sheets: the held copy is a draft of the whole template, the revision number is set by the database per version, the register page is generated and stays one sheet | `SheetPages`, `RegisterLayout`, `ChangeRequestRegisters`, `SheetDraftRules`, `ChangeAnchor` | **reasoned** for both; the hash and text of one-sheet documents **proved** (605/605); **needs a change (S–M)** register file side |
| 8 | Fill-ins (start, autosave, submit, review, correction, reportable columns, archive, the pin) | fill formats 1 and 2 untouched; a fill on a version 2 template is format 3 | `FillStructure`, `FillValidator`, `FillOwners`, `FillValues`, V069 guard | **proved** (prototype `PocFill3`); **needs a change (L)** format 3, the reviewer class (**L**, as POC 1) |
| 9 | Upload | the extras reader and every visible worksheet; the worksheet choice only for a single-sheet record | `XlsxReader`, `SheetImportBuilder`, `RawSheet` | **proved** (extras reader on the real file, 42/42 hostile); **needs a change (L)** |
| 10 | Around the sheet (lists, search, audit, sharing, recycle bin, isolation) | nothing reads a grid | isolation harness for new columns | **reasoned**; none |
| 11 | Management Core (two single-sheet records, reviewer columns, a score, a monthly fill-in) | both forms drawn (look c, pictures); check boxes, attachments, per-cell types now in the format | `FillStructure`, evaluator | **proved** for drawing and format; **needs a change** the reviewer class (L), the score formula **reasoned** |
| 12 | Supplier Management (one record with four tabs, 22 per-row formulas, frozen header, conditional colours) | four tabs load as one document; the data tab drawn with its freeze and colours; the formulas of POC 1 | `IdFormulas`, screen | **proved** (`PocSpmTabs`, pictures, POC 1 formulas 29/29); needs several sheets (**M**) |
| 13 | **Per-cell types** | optional `type` on an input cell, validated per kind | `FillStructure`, `CellValues`, validator | **proved** (prototype, 300/300 good, 35/35 hostile); **needs a change (M)** |
| 14 | **Attachments in cells** (and in the register, the held copy) | a list of this fill-in's file identifiers; none in the register or the held copy (they hold no values) | `FillFileRules`, `FileService`, `RecordFileRules` | **proved** as a validator; the lifecycle is **reasoned**; **needs a change (M)** and the removal callback (S) |
| 15 | **Check boxes** | 36 of 36 placed exactly; 16 pairs one yes/no cell | extras reader | **proved** on the real file; **needs a change (M)** for the reader in the product |
| 16 | **The image** | not kept; a header image per sheet is option B | upload, sheet format, ADR-0120 | **reasoned**; M if wanted |
| 17 | **Several sheets**: the register page, the held copy, Revisions, the hash, the footer, the download | register page stays one generated sheet; held copy and Revisions are per version; the hash is over the whole document (tab order counts); footer per tab; one workbook | section 2.3 | hash **proved** (stable under key order, fixed point, 4 tabs); the rest **reasoned**; **needs a change (S–M)** |
| 18 | **Hyperlinks** | `https` only as a cell property; the 17 real ones are dropped and counted | upload builder, sheet format, screen | **proved** (reader, 9 scheme cases); **needs a change (S)** |
| 19 | **The look** (colour, font, size, width, height) | any `#rrggbb`, 16 font keys, widths and heights widened | format, screen, writer | **proved** (distances 0, strict policy 0 violations, 18 pages); **needs a change (M)** |
| 20 | **Formulas** (40 to 43 functions, the growing range) | allowlist, narrowed forms, the token | `IdFormulas`, evaluator, `Whitelist` | **proved** (cases, gate, round trip); **needs a change (M–L)** the interpreter on the server |

**Change requests, approvals and publish in Document Management keep working unchanged** when a template has one sheet (the common case) and when it has several: they act on a *version* and read no cell; the proof for the one-sheet case is that every version 1 document keeps its text and hash (605 of 605), and for several sheets that the hash, the canonical text and the version table's unit do not depend on how many sheets there are. The revision numbers (V080, set by the guard when a version first becomes the one in use) do not read the grid. **Reasoned, not run against the database.**

### 2.7 WP7. The decision

The recommendation of POC 1 (**Option A**: our own grid for view, print, fill and review; the library behind our guards on the server; the author editor decided at SH6) is **not reopened**: nothing here forced it. What the numbers do change is the **size**: the format must be right the first time, and it now carries per-cell types, attachment cells, several sheets, check boxes as cells, a wide look, and a larger formula set. The ADRs carry an "Amendment (POC 2)" each and ADR-0254 is new. The backlog:

| # | Session | POC 1 | POC 2 | What grows |
|---|---|---|---|---|
| SH1 | Format version 2 and the reader | L | **L+L** (two sessions) | the extras reader (check boxes, links, drawing shapes, picture kinds, embedded-file places), every visible worksheet, the worksheet choice, per-sheet limits, wider width and height limits, 5,000-character fixed text |
| SH2 | Formulas on the server | M–L | **L+M** | 40 to 43 functions, the growing range, the Java interpreter with POI as the oracle, the three exception functions if accepted |
| SH3 | The writer, download and print | M | M+S | N worksheets, a footer per sheet, a page set-up per sheet, "(n attachments)" |
| SH4 | The screen, read side | L | **L+M** | per-cell kinds (check box, attachment, list), the look classes with checked hex colours, the tab bar |
| SH5 | The reviewer class and fill-ins | L | **L+M** | fill format 3, attachment lifecycle (removal clears the cell), required attachments at Submit, several sheets in one fill-in |
| SH6 | The author editor | 3 or 1.5 | **3.5 or 2** | it must set the per-cell type and class, the attachment type, the tab titles and fillable flags |
| SH7 | Several sheets | M (optional) | **M (required, before Supplier Management)** | V091: the version-table function per sheet key, the report's `sheet_key` |

About **12 to 14 sessions** with the editor built by us, **10 to 12** with Univer for authors only (POC 1: 8 to 9 and 6 to 7). These are sizes of reasoning, **not measured**. **Order:** SH1 → SH2 → SH3 → SH4 → SH5 (Management Core can start after SH5: two single-sheet records; it needs check boxes, attachments, per-cell types and the reviewer class) → SH7 → Supplier Management (one multi-tab record; needs several sheets) ; SH6 decided at its point; the file side (SH1 to SH3) and the screen (SH4) can partly run side by side.

## 3. What I would tell you before you start the sheet work

How this can fail, with what the proof showed: a check box placed in the wrong cell (36 of 36 exact here; a form with overlapping or very large boxes would use the centre rule, never a guess); a file identifier that points at another fill-in's file (refused by the validator, the removal callback is the open piece); a hostile drawing part (42 of 42 contained); a font name that breaks the layout or injects a style (the name is never used; a key is); a multi-sheet hash that depends on order (the key order does not matter, the tab order does, on purpose); a growing range that moves (the stored token does not; only its drawn text does); a version 1 hash that changes (605 of 605 did not). Also: the first workbook after a start costs about a second of class loading (POC 1); the extras reader adds a quarter of a second to a plant-size file.

## 4. What was NOT run (said plainly)

- **No platform test of any kind**, no `npm run build` of `platform-web`, no Maven verify, no migration: no product code changed. The only builds are of the throwaway Java project and the throwaway harness app.
- **WP2.5: the local object store and the record-files path were not exercised** (the store did not answer; `docker` did not answer; the application was not running).
- **The side-by-side comparison with the spreadsheet program** is yours (pictures and checklist are ready). The prototype was compared with the file through numbers (distances, pixels, 0 mismatched colours), and one picture was looked at by me.
- **A screen reader, a touch device, other browsers** (Chromium only, as in POC 1). Dark mode: one picture of one tab, not judged for legibility.
- **A Java interpreter of the stored formula form** (POI's evaluator and our browser evaluator were the two sides; the Java interpreter is the first part of SH2).
- **The documented behaviour of the 225 formula cases was written by me from the function documentation and was not run in the spreadsheet program** (the 29 real formulas were compared with the program's own stored values, in POC 1 and again here).
- **Applying an attachment type by hand to the 18 marked cells** (the editor does not exist), the **upload of the plant workbook into a single-sheet record with a worksheet choice** (not built), a **header image** (not built), **fonts shipped by the platform** (not measured), **printing** of the drawn pages.
- **The real filled example** does not exist; the fill-in sizes are generated.
- **The real forms in the product's own screens**: only the harness drew them.

## 5. Commands to run everything again (from `platform/`; the long ones are yours to run)

Prerequisites as POC 1 (JDK 25, Node 24, a Chromium-based browser, the two real workbooks copied to a folder **outside the repository**, called `$DATA`, as `plant.xlsx` and `spm.xlsx`; the neutral JSON fixtures `plant.json` and `spm.json` made as in POC 1 section 5).

```
# the throwaway Java project (POI 6.0.0)
./mvnw -B -ntp -f spikes/sheet-engine/pom.xml package -DskipTests
cd spikes/sheet-engine
CP="target/classes;target/lib/*"
java -cp "$CP" spike.Extras $DATA/plant.xlsx $DATA/spm.xlsx                 # WP1: what the extras reader finds (counts and positions)
java -Xmx300m -cp "$CP" spike.ExtrasHostile                                # WP1.6: 42 hostile cases
java -cp "$CP" spike.ExtrasExport $DATA/plant.xlsx $DATA/plant-extras.json  # positions for the pictures (no text)
java -cp "$CP" spike.FunctionSupport                                        # WP5.1
mkdir -p target/out
java -Duser.language=en -cp "$CP" spike.FormulaCases ../sheet-grid/lib/poc2-formula-cases.txt target/out/poc2-poi-results.tsv
java -cp "$CP" spike.GateCheck2 ; java -cp "$CP" spike.GrowingRangeCheck    # WP5.3 and 5.4
# the harness that needs the platform's classes (compiled once, with their tests): see POC 1 section 5 for the class path file
export APP_TEST_CP=<file holding the test class path of platform-app>
sh run-harness.sh app.platform.sheets.internal.PocV2Shim $DATA/plant.xlsx   # the 605 again, the four-sheet hash
sh run-harness.sh app.platform.sheets.internal.PocFill3                     # WP2: good and hostile fill-ins
sh run-harness.sh app.platform.sheets.internal.PocSpmTabs $DATA/spm.json    # WP3: four tabs as one template
# with the local services up and S3_ACCESS_KEY, S3_SECRET_KEY, S3_PORT set from infra/local/.env (never printed):
sh run-harness.sh app.platform.data.internal.PocStoreProbe                  # WP2.5 (not run here)

cd ../sheet-grid
node scripts/poc2-gen-css.mjs ; npm ci ; npm run build                      # the harness app (a few minutes)
POC2_POI=../sheet-engine/target/out/poc2-poi-results.tsv POC1_DATA=$DATA node --test lib/poc2Formula.test.mjs   # WP5 browser side
POC1_DATA=$DATA node poc2-look.mjs                                          # WP4.1 distances
POC1_DATA=$DATA OUT=<folder outside the repository> node poc2-look-shots.mjs  # WP4.2 and 4.3: 19 pictures, strict policy (about 3 minutes)
POC1_DATA=$DATA node poc2-serve.mjs                                         # look at the pages yourself
```
