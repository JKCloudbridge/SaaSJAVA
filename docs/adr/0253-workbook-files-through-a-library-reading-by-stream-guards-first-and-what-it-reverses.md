# ADR-0253: Workbook files through a library: reading by stream, our guards first, and what this reverses

- **Status:** Proposed (proof of concept POC 1, 2026-11-12; nothing in the product is built from it until the product owner accepts it)
- **Date:** 2026-11-12
- **Track:** Functional track, proof of concept (no product code)
- **Related:** [ADR-0251](0251-sheets-format-version-2-and-the-engine.md), [ADR-0252](0252-formulas-by-identifier-the-stored-form-the-whitelist-and-the-translators.md), [ADR-0195](0195-upload-of-a-spreadsheet-into-a-sheet.md) (decision 2 is reversed here), [ADR-0201](0201-view-and-download-of-a-template-sheet-view-only-the-footer-row-and-revisions.md) (decision 5 is reversed here), [ADR-0250](0250-a-spreadsheet-comes-in-as-it-looks-borders-blank-cells-and-the-title-row.md), [ADR-0012](0012-observability.md)
- **Evidence:** `docs/spikes/spreadsheet-engine-spike.md` (WP1); `spikes/sheet-engine/` (`PoiSafe`, `Whitelist`, `Breakdown`, `Measure`, `ExportNeutral`, the harness `PocHostile`)

## Context

ADR-0195 chose **our own reader over the JDK** for uploaded workbooks ("a full spreadsheet library reads far more than a sheet holds, is large, and has had zip-bomb and entity problems of its
own") and ADR-0201 wrote the download with **our own writer**. Both were right for a sheet of five column types, bold, italic and four sizes. The two real forms need fonts, fills, borders,
number formats, frozen panes, conditional colours, formulas and a calculation that agrees with the spreadsheet program. The reader would grow by the styles, theme colours with tints, indexed
colours, number formats, conditional-format styles and the formula text; the writer by the same; and the calculation has no counterpart at all. A library already does all of it, and it is
the one component that can say what a spreadsheet program would have shown. The product owner accepted the library as a dependency (2026-11-12).

## Decision

1. **The library is Apache POI (version 6.0.0 at the time of the proof; Apache 2.0, with a BSD jar and Apache jars beneath it: 13 jars, 19 MB).** Only the `poi`, `poi-ooxml` and
   `poi-ooxml-lite` modules; never the full schema bundle, never the scratchpad modules, never the modules for other formats. Pinned in the parent POM like any dependency.
2. **Reading is by stream, not by object.** The workbook object (`XSSFWorkbook`) builds the whole file in memory: at the 5 MB cap it took 3.9 to 5.0 s cold and needed 200 MB. POI's event
   reader (the styles table, the shared strings, then each worksheet through a SAX parser) took **1.41 to 1.81 s cold** and fits in **100 MB**. The upload uses the event reader; the workbook
   object is used only to **write** a file and in tests.
3. **Our guards come first, the library second.** The order, kept from ADR-0195: who may change the sheet (`SheetOwners`), the announced length, the bytes read with a count, then **our** archive scan,
   our counts while reading, and only then the library on the same bytes. The proof showed the library alone is **not** a guard: with its own limits (inflate ratio, entry size, entry count,
   text size, array size) it still **accepted** a macro file, 2,001 and 300,000 merged areas, 250,000 cells, a far-away cell, a 100,000-character formula and a 5,000-deep formula. The
   guards that stay ours: the size of the file and of each part and of the whole, counted while inflating (also for parts that are skipped); the number of parts; **duplicate part names refused**
   (the class of CVE-2025-31672; the current reader accepts them and must be fixed whichever way this goes); part names with `..` or a back slash refused; macros refused (a VBA part or a macro
   content type); the number of cells, rows, merges and formats; the length of a text; the length and nesting of a formula **before** the library's parser sees it (it overflows its stack on a
   flat sum of about 50,000 terms). The library's own limits are set as well (they are global to the process, set once).
4. **Never write the owner's file.** The library opens a file read-write by default and writes it back on close. The upload opens **from bytes** only (the file is kept nowhere, ADR-0195),
   and a test fails if any path of the module opens a file by name.
5. **What is skipped, and counted, never run or followed:** hidden worksheets (counted), form controls, embedded objects, drawings, pivot tables, external-link parts, defined names, macros
   (refused: the owner decided on 2026-11-12 that macros are not wanted), comments and hyperlink targets. A formula that reaches another workbook or another sheet is not kept as a formula
   (its last value comes in, as today, with a note). The real plant workbook carries 82 form controls and 18 embedded objects, the real supplier workbook 5 external links, a pivot table and
   136 defined names; refusing them would refuse the owner's own forms.
6. **The library's logger is switched off** (`org.apache.poi.openxml4j.opc`): it printed the file addresses held by the external links of the supplier workbook into the error log, which is
   text of the organization (ADR-0012).
7. **Writing.** A download is written by the library from the stored version (styles from the closed lists, formulas from the stored form drawn at their row, the stored result written as the
   cached value, the footer row, the page set-up of ADR-0201). A text that starts with `=`, `+`, `-` or `@` is never a formula: it is written as text with the quote prefix. The file is
   bounded as today (5,000,000 bytes). The cached values mean a spreadsheet program shows the right numbers before it recalculates.
8. **One time to add: the first workbook after a start costs about one second of class loading** (0.9 to 1.2 s cold for the two real files). It is paid on the first upload or download, never at
   start-up; a warm-up on a background thread is possible and is not decided here.
9. **Same for both screens.** This decision is the same whether the screen is our own grid or a third-party component (ADR-0251): the file side is ours either way.

## Consequences

- A new dependency set (13 jars) in the dependency scan; Apache POI advisories are watched (none open for 6.0.0 at the time; CVE-2025-31672 is fixed from 5.4.0).
- ADR-0195 decision 2 and ADR-0201 decision 5 are replaced; the rest of both (what comes in, the shape, the order of checks, never leaked, last changed by, the footer row, view-only) stands. The
  hostile corpus (`hostile-spreadsheets.txt`) and its test stay as the gate and gain lines for: duplicate part name, `..` in a part name, the stack-overflowing formula, a formula over 4,000
  characters, deep nesting, 300,000 merges, a far-away cell, a macro content type.
- `XlsxReader` (800 lines) and `XlsxWriter` (455 lines) are retired in the sprint that builds this, after a side-by-side run on the corpus and on the owner's files; their guards are ported, not lost.
- **Not decided here:** reading the legacy binary format (still refused), password-protected files (refused), and a choice of worksheet on upload (the supplier workbook's data sheet is the third
  visible one, and its first sheet is a note: ADR-0251 asks for the choice).

## Amendment (POC 2, 2026-11-12): the reader of what the library does not expose

Adds to the decisions above (`docs/spikes/spreadsheet-fidelity-spike.md`, section 2.1). Nothing above is withdrawn; decision 5 ("skipped and counted") is **refined** for check boxes.

**A. Our own small reader for the parts the library's streaming reader does not give.** The plant form's 36 check boxes are old-style **form controls** that the library's streaming reader does not expose. The product reads them with a reader of our own, after the same guards as the rest (decision 3), **from bytes only**: the sheet part lists each control with its anchor (cell and offset, to cell and offset) and the control's own part holds its type and ticked state; the old-style drawing part (not well-formed XML) gives the caption ("Yes", "No") by a bounded text scanner that expands no entity and runs nothing. The reader keeps **no text** (no name, caption, link, file name or picture byte): counts, cell positions and stable keys only. Bounds: 1,000 controls, 5,000 links, 5,000 shapes, a link of 2,000 characters, a drawing part of 4 MB, the XML reader's own depth limit; a document type in any of these parts is refused.

**B. The rules.** A box is placed in the cell that holds the whole of it (**36 of 36**); a box that spans cells takes the cell holding its centre; a box outside the table takes the nearest cell within three cells or is not placed (reported, never guessed). A Yes box and a No box in one cell, in one row up to 4 cells apart or in one column up to 2 rows apart are **one question** and one yes/no cell (16 of 16 pairs). A caption is matched to its box by the shape number, and when the sheet and the drawing number a box differently, by position in the same cell (13 of the 36). Hyperlinks: `https` only, at most 2,000 characters, a host, no user information, kept as a **cell property** (ADR-0251 amendment); every other scheme, a link inside the workbook and a malformed link is dropped and counted (the real form's 17 links are all inside the workbook). Drawing shapes (82 rectangles, 32 with text), embedded objects (18, with the cells they stood in), pictures (typed from their first bytes: PNG, JPEG, metafile, other; **a metafile is never decoded and the scratchpad module is never added**): skipped and counted, never run or followed. Notes read: "36 check boxes were turned into yes/no cells", "18 embedded files were not kept", "82 drawing shapes were not kept (32 held text)", "17 links inside the workbook were not kept".

**C. Proof.** On the real plant form: 36 of 36 exact, 16 pairs, 4 single boxes, 0 unplaced, 220 to 277 ms cold. **42 hostile cases** (1,001 controls; an anchor at column 16,000 and row 1,000,000; negative, text and reversed anchors; seven bad link schemes; a link of 3,000 characters; 5,001 links and shapes; a document type with an entity bomb or an external entity in a drawing and in a control part; a drawing part inflating to 30 MB; a drawing nested 20,000 deep; a relationship leaving the archive; a picture part whose bytes are a program or markup; a part name repeated but for the case; an old-style drawing of 5,001 shapes or 5 MB) are all refused or contained with no crash, stack overflow, memory error or time out (the slowest 424 ms under a 300 MB heap). No scratchpad-module class is on the class path or loaded; the owner's file is never opened for writing.

**D. The hostile corpus** (`hostile-spreadsheets.txt`) gains a line for each of these classes in the sprint that builds the reader.

**E. Worksheets.** The upload reads **every visible worksheet**, one sheet each (hidden ones skipped and counted); a **choice of worksheet** is offered only when the target record is a single-sheet one (the plant workbook has two visible worksheets).
