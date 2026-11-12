# Spreadsheet engine proof of concept (POC 1)

- **Status:** development done 2026-11-12; waiting for the product owner's reading. Not product code. Nothing here is built for real until the owner has read this report and accepted an ADR.
- **Date:** 2026-11-12, branch `POC1` (from `S30-Functional`, commit `8ea7167`), nothing committed.
- **Prototype:** `spikes/sheet-engine/` (Java, POI) and `spikes/sheet-grid/` (browser). Disposable. No change under `platform-app/src`, `platform-web/src`, any `pom.xml` or `package.json` of the platform, no migration.
- **Decisions this feeds:** [ADR-0251](../adr/0251-sheets-format-version-2-and-the-engine.md), [ADR-0252](../adr/0252-formulas-by-identifier-the-stored-form-the-whitelist-and-the-translators.md), [ADR-0253](../adr/0253-workbook-files-through-a-library-reading-by-stream-guards-first-and-what-it-reverses.md) (all Proposed).

## Summary in plain words

1. **The server side works with Apache POI (6.0.0) if the file is read as a stream and our guards come first.** All 29 real formulas equal what the spreadsheet program itself stored. The library alone is not a guard (it accepted
   macros and floods of cells), and its formula parser can crash on a very long sum: our limits must come first. Whole-file reading is too slow and heavy at the 5 MB cap; streaming is fine (1.4 to 1.8 s cold, 100 MB).
2. **Formulas stored by identifier work**: all 29 round-trip to identical text, an inserted row or column rewrites nothing, and both of your real forms fit the model exactly.
3. **Version 1 stays untouched**: 605 of 605 documents keep their text and hash; a version 2 document (a list of sheets, one for now) cannot be stored by accident.
4. **Univer 1.4.0 draws your forms very well and is fast, but** it fails my accessibility thresholds (no cells for a screen reader; Tab did not reach its toolbar), its own cell protection protects nothing (20 of 20 leaks), and it only
   holds when **our own guard** sits in front of it (0 of 220 leaks). Our own grid, extended, passed every threshold and is 592 prototype lines.
5. **Recommendation: A** (your rule), with the file side and format first, our grid for view, print, fill and review, and **a decision point for the author editor** (build it, about three sessions, or use Univer for authors only,
   about one and a half): see section 3.
6. **Found on the way**: the current upload refuses your supplier workbook (a 1,172-character note on its first sheet) and accepts a duplicate part name in the archive.

## 0. Answers given by the product owner before the work started (2026-11-12)

| Question | Answer |
|---|---|
| Do authors build and edit formulas in the browser? | **Yes.** |
| Is Apache POI acceptable as a dependency? | Yes (behind our guards, and only if WP1 passes). |
| Several sheets in one template? | Not now: one sheet per template record. WP7 only checks feasibility. |
| Hidden sheets on upload | **Skipped**, and counted in the notes. |
| A real filled example | None yet; counts only will be recorded when one arrives. |
| Fonts | A **closed list** of font families (like the four named sizes), never a name taken from a file. |

The rule fixed before measuring (from the prompt): Univer passes WP2 **and** authors must build formulas in the browser, so **B-prime**;
Univer fails locked-cell control or accessibility, so **A** (keeping the adapter). Because the answer to the first question is "yes",
the deciding question is WP2.

## 1. Pass thresholds, written before any number was seen

Written 2026-11-12 before the first measurement. They may be tightened, never loosened (the rule of ADR-0082). The first rows are the
prompt's own numbers; rows marked *(added)* are mine, set now for the same reason.

| WP | Pass condition |
|---|---|
| **1 POI** | (a) Every font, fill, border, number format, merge, column width, row height, frozen pane, hidden sheet, conditional format and print area of both workbooks is read and counted. (b) All real formulas are found (7 in the plant file, 22 in the supplier file) and text that starts with "=" is classified as a formula **0 times**. (c) Every result of the whitelist evaluator equals the spreadsheet program's cached value within 1e-9, or the difference is explained and that function or case stays out of the whitelist. (d) Every hostile file (the 57 lines of `hostile-spreadsheets.txt`, run through our own guards, plus the new cases) is refused or contained inside the limits; a macro file and an external link are refused. (e) Time under 2 s and heap under 300 MB at the 5 MB cap; the **cold** single run must pass, the median of five is reported next to it. (f) *(added)* No POI class is loaded when the application starts (loaded on the first workbook only). (g) *(added)* Every runtime jar in the dependency tree has an MIT, Apache 2.0 or BSD licence; the list is in the report. (h) The newest security advisories for the chosen version are checked on the day and written down. |
| **2 Univer 1.4.0** | (a) Every content-policy exception under the strict policy is listed, and the owner accepts each one. (b) No network request after load; no `eval`. (c) Ready time under 3 s on this laptop. (d) *(added)* Typing latency p95 at most 100 ms and at most 2 frames over 50 ms while scrolling, on the 59-column supplier sheet with its frozen pane. (e) *(added)* Added weight at most 2.5 MB gzip, loaded only on sheet pages. (f) **Cell protection:** for each of typing, delete, cut, paste inside a class, paste across a mix of classes, paste from outside, drag-fill, undo, redo, find and replace, row insert and delete, and merge, tried on at least 20 random layouts of fixed, filler and reviewer cells and two roles: **0 fixed cells (or cells of a class the role may not change) changed in the browser.** A cell that changes and is put back afterwards counts as a **failure**, because the person would see it change. Each operation is recorded as *blocked*, *filtered to the permitted cells* or *not controlled*. (g) A keyboard path exists for every function the author needs. (h) *(added)* An author can type, edit and see the result of a formula in the browser (this is what "authors build formulas" means). |
| **3 Own grid, extended** | (a) Both workbooks draw with every style kind the format would carry (font family from a closed list, size, bold, italic, underline, colour, fill, borders, number format, alignment, wrap, frozen pane, conditional colour). (b) The browser evaluator equals the cached values within 1e-9 on the 22 supplier formulas. (c) The grid keeps one grid role, one tab stop and a visible focus ring; dark mode comes from tokens only. (d) *(added)* An author can type a formula in a formula bar and see it recalculate. Lines of code, what is missing compared with Univer (drag-fill, styled paste, find and replace) and what we would own are reported, not pass or fail. |
| **4 Formulas by identifier** | (a) All 29 real formulas of both workbooks round-trip to **identical text** and **identical values**. (b) Inserting a row by the author rewrites **0** stored formulas. (c) A reference outside the sheet is refused every time. (d) A row added by the filler gets the pattern of its formula, and a total over a range keeps pointing at the same first and last row ids. |
| **5 Everything still works** | (a) No blank row in the matrix; every "proved" names the run that proved it. (b) Every version-1 document of the validator corpus, the sample grids and the fill documents of the tests validates through the version-2 shim with a **byte-identical hash**; an archived fill-in stays readable. (c) The inventory is made by searching the code, with paths. |
| **7 Several sheets (paper and a small probe)** | (a) A format-version-2 document with a list of sheets carries every version-1 document as "one sheet, default key" with **the hash of the version-1 document unchanged** (proved by running the stored fixtures). (b) Every seam of the prompt has a written answer, a size and an order of work. |
| **6 Decision** | The owner's rule as written; if the numbers contradict it, say so plainly. |

## 2. Results

(Filled in as each work package is measured. Nothing below this line was written before the thresholds above.)

### WP1. Apache POI on the server

Version tested: **Apache POI 6.0.0** (the newest on the package registry on the day, released 2026-10-22; the prompt assumed 5.x), Java 25.0.4 (Temurin), no compiler or
runtime problem. Code: `spikes/sheet-engine/` (`Inspect`, `Whitelist`, `FormulaCheck`, `WriteSample`, `PoiSafe`, `MiniXlsx`, `Measure`, `Breakdown`, and the harness
`src/harness/.../PocHostile.java`). Both workbooks were read from **copies** in a folder outside the repository; nothing of them is in the repository, and only counts are
printed.

**1.1 What is in the two workbooks (counts; POI reads all of it)**

| | Plant activity workbook | Supplier workbook |
|---|---|---|
| Worksheets (hidden) | 2 (0) | 7 (**3 hidden**) |
| Main sheet: cells listed / bordered / filled / wrapped | 646 / 591 / 417 / 275 | data sheet (the 3rd): 468 / 354 / 199 / 175 |
| Merged areas | 93 on sheet 1, 11 on sheet 2 | 16 on the data sheet |
| Font families used | Calibri, Cambria | Arial, Calibri, Times New Roman, Aharoni, Arial Black, Arial Unicode MS |
| Font sizes used (pt) | 9, 10, 11, 12, 13, 14, 16, 22 (sheet 2: 14, 20, 26) | 8 to 20 |
| Fill kinds | plain colour, theme colour, theme colour with tint (4 on sheet 1) | indexed palette, plain colour, theme with tint (up to 10 on one sheet) |
| Number formats | `0%`, `0.00` | `0%`, `0.00`, `0`, `mmm-yy`, `m/d/yy`, `#,##0`, an accounting format |
| Frozen pane | none | **data sheet: 3 rows** |
| Conditional formats | 0 | **2 rules** (cell value against a limit: fill and font colour) |
| Print area / fit to page | sheet 2 has a print area; sheet 1 fits to page | sheet 1: print area, fits to page |
| Other objects | **82 form-control shapes, 36 control property parts, 18 embedded objects, 17 hyperlinks** | **5 external links, a pivot table and its cache, 136 defined names** |
| **Real formulas** | **7** (5 + 2): all `SUM`, 3 over a range | **22**, all on row 4 of the data sheet: `IF` 24 uses, `MIN` 6, `SUM` 1; 13 hold a text constant |
| the spreadsheet program's stored result of a formula | 7 numbers | 20 numbers and **2 errors** |
| Text that starts with "=" (not a formula) | 0 | **11 lines on each of two sheets; 0 classified as formulas** |
| Longest text in one cell | 281 characters | **1,172 characters** (one note on the first sheet; the sheet limit is 1,000) |

Findings the prompt did not list: (a) the plant workbook carries form controls and embedded objects, the supplier workbook external links, a pivot table and 136 defined names;
all of them are **skipped and counted**, never run or followed. (b) The sizes (8 to 26 pt) and the six font families are far beyond today's four named sizes. (c) **The current
upload reads the first worksheet only: for the supplier workbook that is an instruction sheet, not the data sheet, and it refuses the whole file because one note is 1,172
characters long.** The upload therefore needs a *choice of worksheet* and a decision about long fixed text.

**1.2 The formulas against the values the spreadsheet program stored (threshold 1e-9)**

| Check | Result |
|---|---|
| Real formulas found / inside the whitelist | 7 of 7 and 22 of 22 |
| POI evaluator result equals the value the spreadsheet program stored | **29 of 29**, including the **2 cells where the spreadsheet program stored an error** (same error kind) |
| Small cases (blank in arithmetic, number as text, text in arithmetic, divide by zero, error through `IF`, blank guard, `SUM`/`MIN`/`MAX` over blanks and text, `ROUND` half up and of `2.675`, `AND`/`OR` with a blank reference, `ISBLANK`, case-insensitive comparison, `1+TRUE`, the supplier pattern with a blank, a value, and the cap of 100) | 33 of 33 equal to **documented** spreadsheet behaviour (not run in a spreadsheet program) |
| Differences | **0**. (My first run showed 2; both were arithmetic slips in my expected values, corrected and recorded; the evaluator was right.) |

**1.3 Writing a file.** `spikes/sheet-engine/target/out/poc1-sample-styles-and-formulas.xlsx` (4.7 KB): fonts, fills, borders, number formats, a merge, a frozen pane, a conditional
colour, formulas with stored results, a hidden second sheet, A4 fit to width. POI reopened it and **15 of 15** read-back checks passed. **Opening it in a spreadsheet application
is the owner's manual step** (not done here).

**1.4 Hostile input** (`PocHostile`). Part A: the platform's own corpus against our own reader: **36 of 36 lines refused with the expected code**. Part B: 23 files (valid
packages), each class built again so that a strict reader reaches the content:

| What stops it | Result |
|---|---|
| Our reader refuses | 15 of 23: the supplier workbook (its 1,172-character note), the three bombs, 101 parts, both macro files, 2,001 and 300,000 merges, 250,000 cells, the 1.5 million empty cells, the far cell, the 12 MB shared string, the two files with a document type declaration |
| Our reader accepts, then POI behind a zip-level pre-scan | 8 of 23 accepted; **0 went wrong** (no memory error, time out, stack overflow or crash): the plain file, the plant workbook, a part named with `../`, a **duplicate part name**, 200,000 cell formats, three formula cases |
| POI **alone**, under its own limits (inflate ratio, entry size, entry count, text size, array size) | went wrong on 0 files, **but accepted** both macro files, 2,001 and 300,000 merges, 250,000 cells, the far cell, the 100,000-character formula, the 5,000-deep formula |
| External entity (XXE) | refused by POI; the secret never reached a cell |

Consequences: POI's own limits are **not enough** (no macro check, no count limits, global static settings); the guards in front of it are mandatory and our reader's counts already
exist. **Two gaps found in our current reader**, closed by the pre-scan: it **accepts a duplicate part name** (the class of CVE-2025-31672: two readers could see two different
sheets) and a part named with `..`. Formula gate: **21 of 21 hostile expressions refused and 7 of 7 good ones accepted** (indirect and web functions, a web service, volatile
functions, text, lookup, an external reference, another sheet, a name, a command string, a file address, the intersection operator, text joining, a union, 40-deep nesting, a
3,000-term sum). The first version let the intersection operator through (it blocked functions); it is now an **allowlist of token kinds**. **POI's formula parser overflows the
stack on a flat sum of about 50,000 terms** (138 ms, `StackOverflowError`): the length (4,000) and depth (32) bounds of the expression engine must be applied to the text
**before** POI's parser sees it.

**1.5 Cost and speed**

| Item | Result | Threshold |
|---|---|---|
| Jars added / size | 13 jars / 19 MB (`poi-ooxml-lite` 6 MB, `poi` 3 MB, `xmlbeans` 2.2 MB, `commons-math3` 2.2 MB; the rest small) | report only |
| Licences | all Apache-2.0 except `curvesapi` (BSD); the six commons jars resolved through their parent pom | pass |
| Advisories (maintainers' page, 2026-11-12) | 3 listed, none affecting 6.0.0 (duplicate part names: before 5.4.0, fixed there; a TNEF issue: scratchpad module only; an old XMLBeans entity issue). A separate low-severity note of 2026-02 about `IOUtils.safelyAllocate` could not be matched to a version | pass; re-check when used |
| Java 25 | works unchanged | pass |
| Start-up | no POI class is loaded until the first workbook | pass |
| 5 MB file (4.95 MB, 151,830 text cells), **whole-workbook route** (`XSSFWorkbook`), cold JVM, 300 MB heap | **3.9, 4.6, 5.0 s**; warm 1.2 to 1.6 s; out of memory at 150 MB, works at 200 MB | **time fails** (limit 2 s, cold) |
| Same file, **streaming route** (POI's event reader: styles and shared strings, then each sheet through a SAX parser with the document type declaration off), cold JVM, 300 MB heap | **1.41, 1.42, 1.49, 1.56, 1.81 s**; fits in **100 MB** | **pass** (time and heap) |
| The two real workbooks, cold: whole route / streaming route | 1.2 to 1.8 s / 0.9 to 1.2 s | pass |
| First workbook after start-up | about 1 s of class loading before any work | note |

**Verdict WP1: passes, provided the upload reads through POI's streaming reader and not the whole-workbook object.** Conditions (a) to (d) pass; (e) fails for the
whole-workbook route and passes for the streaming route; (f) to (h) pass. The streaming route reads styles (fonts, fills, borders, number formats, conditional-format styles),
cell values and formulas; the whole-workbook object is for writing and for tests only.

Other facts to carry: POI **opens a file read-write by default** and would have written the owner's file back on close (it only failed because the file was open elsewhere): the code
must open from bytes or with read-only access, always. POI **printed the external-link file addresses of the supplier workbook to its error log**: that logger
(`org.apache.poi.openxml4j.opc`) must be switched off in production (ADR-0012). Its settings (`ZipSecureFile`, byte-array override) are global to the JVM.

### WP4. Formulas by identifier, and the translators

Code: `spikes/sheet-engine/.../IdFormulas.java` (Java, the server's translator) and `IdFormulaCheck.java`; `spikes/sheet-grid/lib/poc1Formula.mjs` (the browser's copy: translators
and evaluator). **The stored form** is the spreadsheet program's text with each cell reference replaced by a token that names the column and the row by their permanent identifiers: `@{COL}` is that
column on the formula's own row, `@{COL,ROW}` a cell of another row, a third part holds the absolute markers (`c`, `r`): `@{COL,ROW,cr}`. A range is two tokens joined by a colon.
Everything else is kept character for character, so the text round-trips exactly. POI's parser tokens are used as the **cross-check** (our reference finder and POI's references must
agree) and as the gate (the allowlist of 1.4); the translation itself is a small scanner, because POI's tokens carry no positions in the text.

| Check | Result | Threshold |
|---|---|---|
| All real formulas: text identical after "to identifiers and back" | **7 of 7** (plant), **22 of 22** (supplier) | pass |
| References found by our scanner / by POI's tokens | 14 / 14 and 119 / 119 | pass |
| Kinds of reference in the real formulas | plant: 14 references, all to other rows (5 ranges, 4 single cells; totals over a column); supplier: **119 references, every one on the formula's own row** (no other row, no absolute marker) | the model fits both forms exactly |
| A column inserted before column H of the supplier data sheet, values recalculated by POI from the re-drawn text | stored formulas rewritten **0**; drawn Excel-style text changed for 22 of 22; values equal to the spreadsheet program's stored value **22 of 22** | pass |
| A row inserted inside the plant data, same test | stored formulas rewritten **0**; drawn text changed 7 of 7; values equal **7 of 7** | pass |
| A total over `D4:D9`, drawn after a row is inserted inside / after the last row / before the first | `D4:D10` / `D4:D9` / `D5:D10`, as the spreadsheet does | pass |
| References that must be refused (a column or row outside the sheet, row 0, another sheet, an external workbook, a quoted sheet, the `@` and `{` characters, an unclosed text, a range running outside) | **10 of 10** refused, each with its own stable reason | pass |
| A stored formula that names a column or a row that no longer exists | refused (two reasons) | pass |
| Edge cases: absolute markers (`$A$1`, `A$1`, `$A1`), ranges, `"A1"` inside a text constant, function names that look like references (`LOG10`, `ATAN2`), numbers with an exponent | **10 of 10** round trip | pass |
| The pattern of a calculated column drawn at rows the filler adds (rows 5, 6, 57, 500) | text correct 5 of 5; value at an added row 75.0 (expected 75.0); an empty added row stays empty | pass |

**Verdict WP4: passes.** Two facts that shape the design. (1) A filler-added row costs nothing in the format: the pattern keeps no row, so "the pattern instantiated for a new row" is
only the drawing of the same stored text at that row; but **neither Univer nor any engine copies the pattern when a row is inserted** (see 2.6), so the adapter must do it. (2) A row or column
inserted by the author keeps the stored text untouched: only the drawn text changes, which is what the identifiers are for.

**The browser's evaluator** (WP3 uses it; `lib/poc1Formula.mjs`, about 230 lines, tests in `lib/poc1Formula.test.mjs`): 34 of 34 small cases, **29 of 29 real formulas equal to the value the spreadsheet program
stored** (including both error cells), translators and refusals pass, a formula longer than 4,000 characters or deeper than 32 levels refused before it is parsed.

### Version 1 stays readable: the version-2 shim (WP5, WP7)

`spikes/sheet-engine/src/harness/.../PocV1Shim.java` runs the real validator, normalizer and writer. A version 2 document is `{"formatVersion":2,"sheets":[{"key":"main", ...the version 1
body without its format number...}]}`; a version 1 document is read as "one sheet with the default key" and written back by the **unchanged** version 1 path.

| Check | Result |
|---|---|
| Documents run | **605**: 600 generated templates (both dimensions, up to 60 rows by 31 columns, 0 to 50 percent fixed cells), the two minimal documents, the platform's sample form read through the upload (twice) and the owner's plant workbook read through the upload |
| Valid | 605 of 605 |
| Version 1 path: same canonical text and same SHA-256 when checked again | **605 of 605** |
| Read as version 2 (one sheet, key `main`) has the expected shape | 605 of 605 |
| And back to version 1: same canonical text / same SHA-256 | **605 of 605 / 605 of 605** |
| An archived copy (gzip of the canonical text) read back has the hash the row holds | 605 of 605 |
| A version 2 document offered to the unchanged version 1 validator | refused (so no stored version can become version 2 by accident) |

Not run: fill documents were not regenerated (their validator needs a pinned sheet); they are version 1 JSON that is never read by the new code path, and the archive check above
is the byte-level proof. This is **reasoned** for fill-ins, **proved** for grids.

### WP2. Univer in the browser (version 1.4.0, the newest on the registry on the day; the old harness had 1.1.0)

Setup: `spikes/sheet-grid/` (the harness was moved to 1.4.0 and gained three presets: conditional formatting, find and replace, data validation; the old pages and the old results in
`results/` are untouched and were measured with 1.1.0). Production build of Next.js 16.3.8 and React 19.3.0, a Chromium-based browser (version 155), headless, this laptop. Both workbooks are fed from the fixtures the Java
spike wrote from POI's reading (`ExportNeutral`), outside the repository. New files: `components/Poc1Univer.jsx`, `adapters/poc1Univer.mjs`, `lib/poc1Layout.mjs`, `poc1-univer-basic.mjs`,
`poc1-univer-formulas.mjs`, `poc1-univer-classes.mjs`, `poc1-extras.mjs`.

**2.1 Fidelity.** Univer draws both real forms with the fonts and sizes, fills, merged areas, borders, number formats (`12,000`, accounting), the 3-row frozen header of the supplier sheet, the
conditional fill, and recalculates the formulas live. It has a formula bar and a full formatting toolbar. (Screenshots of the forms are **not** in the repository: they show the owner's text.)

**2.2 Content policy, network, `eval`** (the supplier data sheet, with and without the two extra presets):

| Policy | Result |
|---|---|
| none / relaxed (scripts of our own origin, no `eval`, inline styles allowed) | starts; **0 violations**; 2 canvases |
| **strict** (no inline styles: `style-src 'self'`) | starts and draws; **5 violations**: 4 style elements and 1 style attribute (all inline styles), the same count as 1.1.0; nothing else |
| Requests to another origin / requests after the page is ready / `eval` | **0 / 0 / none** (the policies have no `unsafe-eval` and nothing was refused) |

The platform's web code sets **no content security policy itself** (a search of the configuration found none); the 5 exceptions matter only if a deployment sets `style-src 'self'`. They would
then have to be allowed for the pages that hold the grid (`style-src 'unsafe-inline'` or a nonce on the style elements the library creates): **a decision for the owner and the operator**.

**2.3 Weight, speed, memory** (5 cold page loads each, relaxed policy):

| | Core preset | With conditional-format and find-replace presets | Threshold |
|---|---|---|---|
| JavaScript added, raw / gzip | 7,648 KB / **1,966 KB** | 7,917 KB / **2,028 KB** | gzip at most 2,500 KB: pass |
| Ready (ms from navigation) | 1,391 1,137 1,323 1,204 **2,823** (median 1,323) | 1,186 1,147 2,082 1,135 **2,538** (median 1,186) | under 3,000 ms: pass (the worst of five is close) |
| Typing a 26-character text into a cell, key to painted frame, p95 per run (ms) | 19, 30, 19, 18, 18 (p50 about 12) | 18, 49, 31, 44, 18 (p50 about 12.5) | at most 100: pass |
| Scrolling the 59-column frozen sheet across and down, frames over 50 ms per run / long tasks | 1, 0, 0, 0, 0 / 0 | 0 / 0 | at most 2: pass |
| Heap after load | 30 MB | 31 MB | report only |

**2.6 Formulas on the supplier rows** (done before 2.4 because it changes the picture): Univer's own calculation equals the value the spreadsheet program stored on **20 of 22** by value. The other two are the
cells where the spreadsheet program stored an error: Univer shows the error code on the screen and its API returns an empty value for an error cell (a difference of representation, handled by taking the
calculation from the server). Its `getValue` returns the **formatted text** (`" 12,000 "`, `"100"`) for a formula cell that carries a number format: a raw read is needed. An author can type
a formula (`=SUM(1,2,3)+MIN(5,4)` gave 10). **Inserting a row does not copy the formula pattern** (the new row has no formula): the adapter must do it. Filling down with the fill handle's
command does copy it with the references moved. Differences from POI: none in value; the error representation above.

**2.4 Cell classes: can a cell a person may not change be changed?** (`poc1-univer-classes.mjs`; results `spikes/sheet-grid/results/poc1-univer-classes.json`.) Synthetic sheet of 8 rows by 6
columns whose cells are *fixed* (nobody types), *filler* or *reviewer*, a different random layout for each of **20 layouts**, the role alternating (10 as filler, 10 as reviewer); the first rows
always mix the classes so that a block crosses a boundary. **11 operations**, real keyboard and clipboard where a keyboard path exists (typing, Delete, Ctrl+X, Ctrl+C, Ctrl+V of a block, Ctrl+V of text from
outside, Ctrl+Z, Ctrl+Y) and Univer's documented API for the rest (drag-fill, delete a row, merge, find and replace: they dispatch the commands the menus dispatch). Oracle: after the operation every forbidden
cell must hold what it held before; **a cell that changes counts as a leak even if it is put back later**. Four settings: **none** (nothing installed), **events** (our *filter* guard), **block** (our *block*
guard), **builtin** (Univer's own range protection on every forbidden cell: 32 to 35 ranges).

What the library lets us do, found while building the guard: a **paste command names only its first cell** (the size of the paste is worked out later from the clipboard), so a guard on commands cannot know
which cells a paste will change; **every change passes one lowest-level mutation (`sheet.mutation.set-range-values`) that lists exactly the cells**. The *filter* guard rewrites that mutation's parameters
before it runs (the forbidden cells are removed; the "before command" event hands over the live parameters) and cancels the structural commands (move, remove rows or columns, merge) that touch a forbidden cell,
and cancels the start of an edit in a forbidden cell. The *block* guard cancels the whole operation when it touches a forbidden cell. The first version of the filter guard left a half-made merge (cells cleared and no
merge); structural commands are therefore refused at the command, not the mutation.

| Operation | none (8 layouts): leaks | **filter guard** (20): leak / blocked / filtered | **block guard** (20): leak / blocked / filtered | built-in protection (20): leaks |
|---|---|---|---|---|
| type into a forbidden cell (keyboard) | 8 of 8 | **0** / 20 / 0 | **0** / 20 / 0 | 20 |
| type into a permitted cell (must still work) | works 8 of 8 | **works 20 of 20** | **works 20 of 20** | works 20 |
| Delete over a mixed block (keyboard) | 8 of 8 | **0** / 0 / 20 | **0** / 20 / 0 | 20 |
| cut a mixed block and paste (keyboard) | 8 of 8 | **0** / 14 / 6 | **0** / 20 / 0 | 19 |
| copy a mixed block and paste over a mixed block (keyboard) | 8 of 8 | **0** / 2 / 18 | **0** / 20 / 0 | 20 |
| paste text from outside over a mixed block (keyboard and clipboard) | 8 of 8 | **0** / 2 / 18 | **0** / 19 / 1 | 19 |
| drag-fill over a mixed block (API) | 8 of 8 | **0** / 0 / 20 | **0** / 20 / 0 | 20 |
| undo and redo of a permitted edit (keyboard) | no forbidden cell reached | 0 | 0 | 0 |
| delete a row that holds forbidden cells (API) | 8 of 8 | **0** / 20 / 0 | **0** / 20 / 0 | 20 |
| merge a mixed block (API) | 8 of 8 | **0** / 20 / 0 | **0** / 20 / 0 | 20 |
| find and replace (API) | **not exercised**: the call replaced nothing even with no guard | not valid | not valid | not valid |

(*blocked* = nothing changed; *filtered* = only permitted cells changed, forbidden cells untouched; "blocked 2 of 20" on a paste are layouts where every target cell was forbidden.)

Verdict: with nothing installed **8 of the 11 operations change a protected cell**. **Univer's built-in range protection protected nothing** (it leaked on every operation that can leak: 20 of 20; the local user is the
owner and the authorisation service of the open-source package is a local stub): it is **not a control**, as the owner's notes warned. **Our own guard, at the one mutation, held in all 220 runs of the filter setting and
all 220 of the block setting: 0 changes to a protected cell**, while permitted edits still worked (20 of 20). It is our code on a library that moved from 1.1 to 1.4 in three weeks: **the script is the regression
suite and must run on every upgrade**. Not covered by the guard: number formats and other settings the toolbar can change (the fill screen would hide the toolbar: a filler formats nothing), which is a reasoned
mitigation. **Not exercised:** find and replace (its own API replaced nothing; the dialog was not driven; reasoned: it writes through the same mutation), a real mouse drag on the fill handle (the command was
issued by API), touch. **Threshold 2(f): met with our guard, not met by the library alone.**

**2.5 Accessibility, keyboard, dark mode, phone, autosave, several sheets** (`poc1-extras.mjs`, `results/poc1-extras.json`)

| Item | Univer 1.4.0 | Threshold or note |
|---|---|---|
| Accessibility tree (Playwright's snapshot) | 1 main, 6 tabs, 44 buttons, 45 images, 4 text boxes; **0 grid, 0 row, 0 cell** (the grid is a drawing): a screen reader cannot read a cell | the old finding (1.1.0: none, canvas) stands. No screen reader was available: **not run** |
| Keyboard only | arrows move the selection; typing commits; Ctrl+Z and Ctrl+Y run; Ctrl+F opens find. **Forty presses of Tab gave one focus stop** (a div): the 44 toolbar buttons were **not reached by Tab** in the automated pass; the bold shortcut was not confirmed | threshold 2(g) "a keyboard path exists for every function": **not met in the test**; to be checked by hand (manual test B4) |
| What a parallel accessible layer would cost | reasoned: an off-screen grid of the cells in the window (about 600 cells), roles, names, selection and edit kept in step both ways, a live announcement, a screen-reader pass: about 300 to 500 lines and a long test; our own grid already has exactly this (below) | |
| Dark mode | follows **neither** the system setting (brightness of the grid 0.90 light, 0.78 with the system in dark) **nor our tokens**; its own switch gives 0.21 | needs a theme mapped from the tokens (reasoned; the library has a theme object, not tried) |
| Phone width (390 px) | the page does not overflow (scroll width 390); the toolbar is 59 buttons and the grid a canvas; **usability on a real touch screen not measured** | our own grid is read-only below 40 rem and the form view builds (ADR-0172) |
| The autosave pattern (only the changed cells are sent) | by observation of the mutations: a typed value gives one mutation naming one cell, a paste names its cells, fill-down gives a clear and a fill; a patch of exactly the changed input cells can be built from them after the filter. The automated capture in this run was inconclusive | reasoned from the observed mutations |
| **Several sheets in one document (WP7)** | a workbook of the supplier file's 7 worksheets with the 3 hidden ones skipped gave **4 tabs**, switching works and **the frozen pane is kept** per sheet | proved |

**Verdict WP2 against the thresholds written first:** (a) 5 inline-style exceptions listed, acceptance is the owner's (the platform sets no policy itself); (b) pass; (c) pass (worst 2.8 s of 3); (d) pass; (e) pass;
(f) **pass with our guard, fail with the library alone**; (g) **not met in the test** (Tab did not reach the toolbar); (h) pass. Screen-reader access: **fails** (no cells in the tree).

### WP3. Our own grid, extended (a prototype and a cost)

Code (harness only): `spikes/sheet-grid/components/Poc1OwnGrid.jsx` (145 lines), `lib/poc1Closed.mjs` (72: the closed lists, the colour mapping, the ten number formats, the conditional rule), `lib/poc1Formula.mjs`
(240: translators, parser, evaluator), `scripts/poc1-gen-css.mjs` (37) writing `app/poc1-own.css` (98 generated lines: one class per closed value). **592 lines.** It draws a real workbook from the same fixtures as Univer.

| Check | Result | Threshold |
|---|---|---|
| Both workbooks drawn with every style kind the format would carry: font family (4 closed names), size (8 to 28), bold, italic, underline, text colour and fill (36 closed names, a hue mixed with the surface token), four borders in six styles, number format (10 kinds: `0%`, `0.00`, `#,##0`, accounting, `mmm-yy`, `m/d/yy`, ...), alignment, wrap, merged areas, a frozen header band, a conditional colour | yes (screenshots looked at; they show the owner's text and are **not** in the repository). Known gaps of the prototype: columns are not virtualised (rows are not either: the product grid already is, so this is the same cell component); the first version painted vertically merged header cells blank under the row below (a stacking bug, fixed) | pass |
| Closed lists against the real forms | families: 4 names cover all 7 distinct families used; sizes 8 to 28 cover all 13 distinct sizes used (13, 15 and 26 included); colours: a 36-name palette covers 12 of 17 distinct fills within a colour distance of 30 (plant 5 of 6, supplier 7 of 11), the others take the nearest name; the 8 number formats of the supplier file and the 3 of the plant file are all inside the 10 kinds | report |
| The browser evaluator equals the values the spreadsheet program stored on the 22 supplier formulas (1e-9) | **22 of 22** (and 7 of 7 plant, 34 of 34 small cases) | pass |
| Grid role, rows, cells, tab stops, focus ring | **1 grid, 15 rows, 743 cells with names**, 3 tab stops (formula bar, grid, then out); arrow keys move the selection; visible focus ring | pass |
| Dark mode | through the tokens only: brightness 0.94 light, **0.15 dark**, the coloured fills keep their meaning | pass |
| Strict content policy | **0 violations** on both forms (sizes use the element's style properties, colours and fonts classes) | |
| Ready (from navigation, local, fixture included) | 0.8 to 1.5 s | |
| Typing into the formula bar, key to painted frame | p50 14 ms, p95 22 ms | |
| Calculated columns recalculate | typing three inputs of row 4 changed 2 of the 4 watched formula cells (to 100 and 75 as expected); a typed formula `=SUM(1,2,3)+MIN(5,4)` shows 10; `=INDIRECT(1)` is refused with the reason `function_not_allowed` | pass |
| An author can type a formula in a formula bar and see it recalculate | yes (above) | pass (threshold 3(d)) |
| Phone width | no overflow (390 px); read-only below 40 rem as decided in ADR-0172 | |

**What is missing compared with Univer** (not built, estimated): selection of a range and the fill handle; styled paste; find and replace; reference picking by mouse while typing a formula; function help and
completion; a toolbar (font, size, colour, border, number format, freeze, merge); undo and redo of formulas and styles in the existing patch model; inserting a row or a column that copies the formula pattern; a
conditional-colour editor; resizing a column by drag (exists for v1); the several-sheets tab bar (about 40 lines). **How much we would write and keep:** to get an *author editor* with those features we would write about
**3,000 to 4,000 lines of TypeScript plus tests, about three sessions**; for the screens people use every day (view, fill, review, print) the read side of the prototype is the start: about **900 lines of product code**
(the cell component, the closed-list classes, the formatter, the evaluator and translators, the conditional colour), because the existing `SheetGrid` (768 lines) and `gridModel` (685) keep their virtual rows, selection,
paste of values and the accessibility roles. By comparison the adapter and guard for Univer is about **1,000 to 1,500 lines** (quantising to the closed lists on the way in and out, the guard and its regression suite,
copying the formula pattern on insert, a fill mode with the toolbar hidden, a theme from the tokens) with an accessible layer on top if wanted (300 to 500 more).

### WP5. Everything still works (the inventory and the matrix)

**5.1 Inventory** (by search of the code on 2026-11-12, branch `POC1`; paths relative to `platform/`)

| Where | Files that read or write the sheet format, its cells or its file forms |
|---|---|
| `platform-app/src/main/java/app/platform/sheets/` (public, 11) | `FillLifecycle`, `FillRefusal`, `FillReports`, `FillStatus`, `FillSubmission`, `Revisions`, `SheetExports`, `SheetLifecycle`, `SheetMoveRefusal`, `SheetPages`, `SheetStatus` |
| `.../sheets/internal/` (about 50 files; 36 name the format) | model and codec: `SheetGrid` (194 lines), `SheetGridReader` (97), `SheetGridNormalizer` (81), `SheetGridWriter` (147), `SheetGridValidator` (547), `SheetSchemas`, `SheetProperties`, `BlankGrids`, `CellValues`; store and rules: `SheetVersionStore`, `SheetVersions` (392), `SheetOwners` (190), `SheetBodyLimit`, `SheetChanges`, `SheetController`, `SheetModuleConfiguration`, `DefaultSheetLifecycle` (280), `DefaultSheetPages` (122), `DefaultSheetExports`; the file side: `XlsxReader` (800), `XlsxWriter` (455), `SheetCsvReader`, `SpreadsheetImporter` (130), `SheetImportBuilder` (320), `RawSheet`, `ImportNote`, `ImportProperties`, `ImportRefusal`, `SheetImports`; fill-ins: `FillDoc` (59), `FillStructure` (135), `FillValidator` (476), `FillOwners` (141), `FillValues` (92), `FillValueStore`, `FillChanges`, `FillProperties`, `DefaultFillLifecycle` (165), `DefaultFillReports`, `DefaultFillSubmission`, `FillArchive` (176), `FillArchiveJob`, `FillArchiveProperties`, `SheetFills` (218), `SheetFillStore`, `SheetFillController` |
| `.../inspection/internal/` (19) | `ChangeAnchor`, `ChangeRequestApprovalHooks`, `ChangeRequestRegisters` (265), `ChangeRequests`, `FillApprovalHooks` (110), `FillFileRules`, `FillIns`, `FillReport` (217), `QualityHome`, `RegisterFacts`, `RegisterLayout` (277), `RegisterViews` (127), `TemplateApprovalHooks` (203), `TemplateDownloads` (122), `TemplateDraftRules`, `TemplateList`, `TemplatePublishing` (505), `TemplateRevisions`, `TemplateSendRule` |
| other modules | `.../solution/internal/ShippedSheetOwners`; shared kernel `sharedkernel/solution/ObjectRoleDeclarations`, `SheetDraftRules`, `SheetOwnerDeclarations`. **No class of `approval`, `workflow`, `notification`, `audit`, `data` (outside the seams), `security`, `identity` names the format** |
| contract | `platform-api-contract/src/main/resources/schemas/sheet-grid.json` (254 lines), copied into `.../openapi/platform-api-v1.json` |
| migrations | V064 `sheet_version`, V069 `sheet_fill`, V070 `sheet_fill_value`, V071 archive, V080 change register and revision numbers, V082 fill departments and template events |
| backend tests | 32 files name these classes (18 under `sheets/`, 9 under `inspection/`, 2 architecture, 2 under `solution/`, 1 at the root); corpora `platform-app/src/test/resources/sheets/hostile-grids.txt` (132 lines) and `hostile-spreadsheets.txt` (57); the isolation harness lists the sheet tables |
| web | `platform-web/src/components/sheets/` (26 files, incl. `SheetGrid.tsx` 768 lines, `GridView`, `FormView`, `SheetWorkspace`, `FillWorkspace`, `UploadSheet`, `SheetPrintView`, `useSheetEditor`, `useFillEditor`, `useFillAutosave`), `src/lib/sheets/` (23 files, incl. `gridModel.ts` 685, `fillModel.ts` 531, `adapter.ts`, `print.ts`, `revision.ts`, `upload.ts`, `geometry.ts`, `types.ts`), `src/solutions/inspection/` (15 files that draw a sheet or a register), the generated client, `platform-web/browser-checks/sheet-grid.mjs` |

About 7,100 lines of the Java classes above and 6,562 lines of web code (tests excluded) exist today.

**5.2 Matrix.** Design under test: format version 2 (ADR-0251) + POI behind our guards (ADR-0253) + id-based formulas (ADR-0252) + one of the two screens. **proved** = a prototype of this proof ran it (named);
**reasoned** = paper (why it was not run); **needs a change** = the change and its size (S a day or two, M a few days, L about a session). No database or integration test was run in this proof (no product code changed).

| Area | How the design meets it | Seam (class) | Mark |
|---|---|---|---|
| **Template lifecycle**: draft to retired, one draft, review and active per sheet, V064 guards, canonical text and hash, the approver's counted review edit | The version table, its indexes, the moves and the trigger are not about the grid. A version holds a version 2 document; its canonical text and hash come from the new writer. A version 1 version keeps its text and hash. The review edit is the same save with the counted setting | `SheetVersions`, `SheetOwners`, `SheetGridValidator`, `SheetGridNormalizer`, `SheetGridWriter`, V064 function | **proved** for version 1 text and hash (the shim: 605 of 605); **reasoned** for the guards (untouched); **needs a change (L)**: a version 2 branch in the validator, normalizer, writer and reader, and the schema `sheet-grid.json` |
| **Approval process**: steps, any one or all, skip, the step that asks for a choice, hooks in the decision, the record lock, override, reminders, escalation, delegation, history | None reads a cell: the engine acts on a record and the hooks call `SheetLifecycle` moves and read record fields (search: no class of `approval` names the format) | `ApprovalHooks` implementations in `inspection/internal` | **reasoned** (code search); no change |
| **Numbering**: identity pair, Part A and B, the first approval, counters | Fields of the template record and `NumberSeries`; no grid | `TemplateApprovalHooks`, `NumberSeries` | **reasoned**; no change |
| **Publish**: location and departments, unpublish, retire, shares made by the publish, View only, Allow download, the audience suggestion | Records, shares and `inspection_template_option`; the only sheet use is the download (next rows) | `TemplatePublishing`, `TemplateDownloads` | **reasoned**; no change except the download |
| **Footer stamp**: Format, Revision, date, later "submitted by": made at show time on screen, in print and in the file, one text rule | The stamp is never stored. Screen: the existing footer drawing; file and print: the new writer must add the footer row and the page set-up from the same text rule; an editor (either screen) must neither show nor save it | `TemplateSheetTexts.footer`, `revision.ts`, `XlsxWriter` | **reasoned**; **needs a change (S)** in the writer |
| **Download and print**: the file for a template and a held copy, the print view of the register, page set-up | POI writes the file from the stored version (styles from the closed lists, formulas with stored results, frozen panes, conditional colours, hidden sheet, A4 fit to width); the page set-up must stay **one source** for the file and the register's print view | `XlsxWriter` (printSettings), `DefaultSheetExports`, `SheetPages`, `TemplateDownloads` | **proved** that POI writes and rereads all of it (15 of 15 read-back checks; opened by hand: the owner's step); **needs a change (M)**: the new writer, the page set-up moved to a neutral producer before `XlsxWriter` is retired |
| **Change requests**: name, department, register form; the read-only Excel page of the register; the held revised copy seen only by the deciders; the last approval publishes in the decision; gapless Revisions by the database; notes tied to ids; open-request counts; the list across templates; accept and decline | The register page is a generated sheet and stays in version 1 terms; its file side moves with the new reader and writer. The held copy, the hook, the revision numbers (V080), the counts and the list do not read a grid. Notes and places point at identifiers, which do not change (an optional sheet key is reserved) | `SheetPages`, `RegisterLayout`, `ChangeRequestRegisters`, `SheetDraftRules`, `ChangeAnchor` | **reasoned**; **needs a change (S to M)**: port the register's file reading and writing to the new file layer |
| **Fill-ins**: start from a published template, autosave patches, submit with and without a process, review, return, the counted correction, attachments, reportable columns and the report, the archive, the pinned version, the classes fixed / filler / reviewer | Shape B, `sheet_fill` and the archive are unchanged; the pinned version may be version 1 or 2 and `FillStructure` reads the classes from either. The fixed and filler classes are enforced in the browser (see below) and by `FillValidator` as today. **The reviewer class is new**: it needs a guard window like the correction's. Calculated columns and reportable results are computed when the fill-in is saved and sent | `FillStructure`, `FillValidator`, `FillOwners`, `FillValues`, `FillLifecycle`, V069 guard function | **proved** in the browser: Univer with our guard 0 changes to a protected cell in 220 runs (and 0 of 220 with the block guard), permitted edits still work; our own grid draws only input cells as editable (existing); **proved** (by the shim) that a version 1 document and its archived copy keep their hash; **needs a change (L)**: the reviewer class (one migration replacing the guard function with a new session setting, `FillOwners`, `FillValidator`, the lifecycle method, the screens), **(M)** the version 2 branch of `FillStructure` and calculated values in `FillValues` |
| **Upload**: the extent of the table, bordered blanks, the title row, the notes of what was left out, the limits, the hostile corpus, text that starts with "=" | The rules of ADR-0250 live in `SheetImportBuilder` over `RawSheet`; only the reader changes (POI's event reader) and `RawSheet` gains styles and formulas. Guards first (ADR-0253). A choice of worksheet and a rule for a long fixed text are new | `XlsxReader`, `SheetImportBuilder`, `RawSheet`, `SpreadsheetImporter` | **proved**: the platform corpus 36 of 36 refused with its code; 23 more valid-package cases (15 refused by our reader, 8 accepted and then contained, 0 failures); text starting with "=": 0 of 22 such lines classified as formulas; **needs a change (L)** the new reader and builder with the ported guards; **(S to M)** the worksheet choice and the long-text rule (the real supplier workbook is refused today) |
| **Around the sheet**: lists and views, search, the audit trail, notices, record sharing and record-level security, the recycle bin (kept records), tenant isolation | Nothing here reads a grid (code search). A new table or column (`sheet_key`, the reviewer window) needs its line in the isolation harness and the schema scanner | `TenantIsolationIT` harness, `SchemaConventionsIT` | **reasoned**; no change |
| **Management Core**: two forms as separate records; reviewer-only columns; a score and an 85 percent rule on the server; a monthly fill-in per plant | Both forms are **drawn** by both screens (proved: Univer and our own grid, from POI's reading). Two records is the decision. Reviewer-only columns: the new class. The score is a formula on the server (a comparison and `IF` are in the whitelist and in the 34 small cases; the 85 percent rule itself was not run). A monthly fill-in per plant is header-record fields and a uniqueness rule outside the sheet engine | `FillStructure`, the whitelist evaluator, the header object | **proved** for drawing; **reasoned** for the score and the monthly fill-in; **needs a change (L)** for the reviewer class |
| **Supplier Management**: a repeatable data sheet with per-row calculated columns, 59 columns, a frozen pane, conditional colours, hidden sheets skipped | Proved on the real sheet: 22 formulas read, stored by identifier, equal to the spreadsheet program's value in both evaluators, the pattern drawn at rows the filler adds (value 75.0 where expected), the frozen pane and the two conditional rules drawn by both screens, 3 of 7 worksheets reported hidden. The editor must copy the pattern into an inserted row (no engine does) | `IdFormulas`, `poc1Formula.mjs`, the screen | **proved**; **needs a change (S)** the pattern copy; **(S)** the skip of hidden worksheets with its note |

### WP7. Several sheets in one template: feasibility (paper and a small probe; nothing is built)

The first release keeps **one sheet per template record**, so Plant activity and Action plan are two records (the owner's decision of 2026-11-12). This section only answers whether choosing format version 2 now closes the
door on two or more named sheets (tabs) in one template later.

**1. Could version 2 carry a sheet key, with version 1 read as "one sheet, the default key", and the hash of every version 1 document unchanged? Yes, proved.** The shim: 605 of 605 documents. The version 2 shape is
`{"formatVersion":2,"sheets":[{"key":"main", ...}]}`; a version 1 document is never rewritten (the reader dispatches on `formatVersion`), so its text and hash cannot change; read as version 2 and written back it gives the
same bytes and the same SHA-256. The decision that costs almost nothing today is to make version 2 a **list from the start** (one entry allowed), so "several" later raises a limit instead of changing the format.

**2. What changes at each seam if the limit goes from 1 to N** (S a day or two, M a few days, L about a session)

| Seam | What changes | Size |
|---|---|---|
| Version table and its guards (V064: one draft, one in review, one active) | **The unit is the template, not the sheet**: a version row holds all the sheets, so the three partial unique indexes, the moves, the immutability trigger and the numbers stay. The columns `dimension`, `rows_mode`, `grid_bytes` describe the first sheet (or are replaced by a `sheet_count`); the function that fixes the shape of the first version compares **per sheet key** (a migration that replaces it, expand only) | S to M |
| Validator and limits | rows and columns (500 by 100) are **per sheet**; the 1,000,000 bytes is **per template** (all sheets together); a limit on the number of sheets (8 suggested); identifiers unique **within a sheet**; the stages iterate per sheet and a problem's place gains the sheet key | M |
| Fill-ins (`FillDoc`, `sheet_fill`, V069) | **One fill-in covers all sheets of the version it is pinned to**: approval, submit, the lock, the correction, the archive and the header record act on a fill-in; one per sheet would multiply the approvals. `FillDoc` gets a `sheets` map (the version 1 shape reads as the key `main`); `FillValidator` runs per sheet; `row_count` is the sum | M |
| Reportable columns (`sheet_fill_value`, V070) | a `sheet_key` column with default `main`, the unique and typed indexes widened by it (expand only); the report asks a sheet and a column | S |
| Archive (V071) | unchanged (one document) | none |
| Footer stamp | one footer row per worksheet in the file and the print; same text rule | S |
| Download | the writer produces one workbook with N worksheets (a library does this naturally); the file name is unchanged | S |
| Change requests and the register | the register page is its own generated sheet and does not change; a place in a request (`ChangeAnchor`, no longer drawn on screens) gains an optional sheet key | S |
| Approval and publish | act on a version: all sheets move together, **no change** | none |
| Upload | reads **every visible worksheet** (hidden ones skipped and counted, as decided), one sheet each, limits per sheet and per workbook; the choice of worksheet needed today for the supplier workbook disappears | M |
| Formulas (ADR-0252) | the token reserves a fourth part, the sheet key; a formula that names another sheet is refused today and allowed later by adding the key; an unknown sheet key is refused | S |

**3. Does the Univer probe handle several sheets, and does our own grid need a tab bar?** Univer: **yes** (a workbook of the supplier file's 7 worksheets with the 3 hidden ones skipped gave 4 tabs; switching works and
each sheet keeps its frozen pane). Our own grid has no tab bar today: it needs one (a row of buttons that select a sheet, the sheet's own freeze and widths, one more roving tab stop): about 40 lines and a test, S.

**4. Size and order.** One extra session after the sheet engine exists (SH7 below): format and validator per sheet, the V064 function and the V070 column, `FillDoc` and `FillValidator`, the download and the upload, the tab
bar. It is **expand only** and needs no data migration (version 1 is the key `main`). **A bad choice today that would make it harder later:** (a) sheet-level facts (freeze, print set-up, widths) stored on the document
root instead of inside the sheet; (b) identifiers unique across a template, or addresses that carry no sheet part; (c) a formula token that cannot carry a sheet key; (d) a fill-in format that cannot hold a second sheet's
rows. The decisions of ADR-0251 and ADR-0252 avoid all four at no cost now. Nothing in the first release is designed around several sheets beyond that.

## 3. The decision (WP6)

### 3.1 The rule fixed before measuring, applied

- Univer passes WP2 **and** authors must build formulas in the browser: recommend **B-prime**.
- Univer fails locked-cell control or accessibility, **or** authors only upload from a spreadsheet file and make light edits: recommend **A**, keeping the adapter so the component can be added later for authors only.

Authors **do** build formulas in the browser (answer of 2026-11-12). Does Univer pass WP2? **No, by the thresholds written first:** (g) a keyboard path for every function was **not met in the test** (Tab never reached the toolbar,
44 buttons) and it exposes **no cell to a screen reader**; (f) holds **only with our own guard** (the library's own protection leaked on 20 of 20 runs); (a) needs the owner's acceptance of 5 inline-style exceptions. So the
rule gives **A**, "keeping the adapter so the component can be added later for authors only".

**Do the numbers contradict the rule? Not its answer, but they show a cost the rule's wording did not foresee.** The rule was written for authors who "only upload and make light edits". With authors building formulas, the
**author editor is the most expensive part of A** (about 3,000 to 4,000 lines, about three sessions) and exactly the part where the library is strongest; and for an author the locked-cell risk **does not exist** (an author may change
every cell; the classes are what an author assigns), so for an *authors-only* Univer the failures shrink to the screen reader, the Tab path (to be checked by hand) and the 5 style exceptions. That is why the recommendation below has
a decision point.

### 3.2 Comparison with the numbers beside each criterion

| Criterion | A: our own grid, extended | B-prime: Univer 1.4.0 as the screen |
|---|---|---|
| The file side (read, write, calculate) | **the same for both**: POI behind our guards (stream read 1.41 to 1.81 s cold, 100 MB), id formulas (29 of 29 equal to the spreadsheet program's stored value) | the same |
| The two real forms drawn | yes, from closed lists (4 families, sizes 8 to 28, 36 colours: 12 of 17 fills within distance 30, the rest nearest) | yes, with any font and colour; must be **quantised to the closed lists** on the way back, or a person sees a colour change after saving (a snap at edit time through the same guard is reasoned, not tried) |
| Authors build formulas in the browser | formula bar, typing, refusal with a reason: built (592-line prototype); reference picking, completion, fill, paste, insert-with-pattern, undo of formulas: **about 3 sessions** | built in: formula bar, typing, fill-down copies the pattern; **no engine copies the pattern into an inserted row** (about 100 lines) |
| A cell a person may not change cannot be changed in the browser | the grid edits only input cells (existing); reviewer class is new; the server decides anyway | **0 of 220** with our guard, **20 of 20 leaks** with the library's protection; our code on a fast-moving library, the script must run on every upgrade |
| Keyboard | arrows, formula bar, tab stops: yes | arrows, typing, undo: yes; **Tab did not reach the toolbar** (to be checked by hand) |
| Screen reader | **1 grid, 15 rows, 743 named cells** | **none** (a layer of 300 to 500 lines would be needed) |
| Dark mode through our tokens | yes (0.94 light, 0.15 dark) | no (0.78 under the system setting, its own switch 0.21); a theme from the tokens needed |
| Strict content policy | 0 violations | 5 (inline styles); the platform sets no policy today |
| Weight | none added (the existing 17 KB virtual-scroll helper) | **+1,966 KB gzip** (+2,028 with two presets), loaded on sheet pages only |
| Ready / typing p95 / scrolling | 0.8 to 1.5 s / 22 ms / not measured at size (the product grid is virtual) | median 1.3 s, worst 2.8 s / 18 to 49 ms / at most 1 frame over 50 ms |
| Phone | read-only below 40 rem, the form view builds | canvas, 59 buttons; not measured on a touch screen |
| Several sheets later | a tab bar, about 40 lines | built in; frozen pane kept per sheet (proved) |
| Cost of the screen | read side about 900 lines; author editor about 3,000 to 4,000 lines | adapter, quantiser, guard and its suite, pattern copy, fill mode, theme: about 1,000 to 1,500 lines (an accessible layer 300 to 500 more); **the read side is still wanted** for view, fill, review and print if those must be accessible |
| Main risks | the editor grows; author features arrive slowly | library churn (1.1.0 to 1.4.0 in three weeks; guard on internals), canvas not accessible, paid add-ons not available (xlsx conversion is not used: our file side is POI) |

### 3.3 The sized backlog (each expand-only, one per session; sessions are about a day of work)

| # | Session | Contains | Size |
|---|---|---|---|
| SH1 | Format version 2 and the reader | the schema and a version 2 branch of validator, normalizer, writer and reader; the closed lists; POI's event reader behind our guards (duplicate part names refused, macros refused, counts, the logger off); the upload with the choice of worksheet, hidden sheets skipped and counted, the rule for a long fixed text, the notes; corpus lines added | L (may split in two) |
| SH2 | Formulas on the server | the stored form, the translators (Java and TypeScript), the whitelist and bounds, the interpreter, results stored with their kind, `SUM`, the refusal corpus, POI's evaluator as the test oracle | M to L |
| SH3 | The writer, download and print | POI writes the file (styles, formulas with stored results, frozen panes, conditional colours, print set-up, footer), the register page ported, `XlsxReader` and `XlsxWriter` retired after a side-by-side run | M |
| SH4 | The screen, read side | styles, closed lists, borders, number formats, frozen band, conditional colour, formulas shown, in view, print and fill modes; accessibility and tokens kept | L |
| SH5 | The reviewer class and fill-ins | the migration (guard window), `FillOwners`, `FillValidator`, calculated columns and reportable results, the review screens | L |
| SH6 | The author editor | **either** built in our grid (about 3 sessions) **or** Univer for authors only behind the adapter (about 1 to 1.5 sessions) | decided at this point |
| SH7 | Several sheets (optional) | see WP7 | M |

About **8 to 9 sessions** with the author editor built by us, **6 to 7** with Univer for authors only. Management Core and Supplier Management can start their forms after SH4 and SH5.

### 3.4 What a person will not be able to do (either option)

Choose a free font or colour (a name from the closed lists), use a function outside the nine, refer to another sheet or another workbook, keep a macro (refused), a pivot table, a chart, an image, a form control or an
embedded object (skipped and counted), a data-validation list or a conditional format other than "a cell value against a limit" (counted), a password-protected or legacy binary file (refused), or see a hidden worksheet
(skipped and counted). Under A, until SH6 is built, an author cannot select a range and fill it down or pick references with the mouse.

### 3.5 Recommendation

**Choose A**, as the rule says: the screens that people use every day (view, print, fill, review) in our own grid, which passed every threshold of WP3 (accessible, dark mode by tokens, no policy exception, a formula bar). Build the common file
side and the format first (SH1 to SH3). **Keep the adapter, and decide the author editor at SH6** between building it (about three sessions) and Univer for authors only (about one and a half). If the owner can accept for
authors only that the editor is a drawing a screen reader cannot read, and the 5 style exceptions on that one page, Univer for authors is the cheaper and lower-risk path (no locked-cell question, no guard needed); if the
accessibility of the editor matters, build it. **I do not recommend Univer as the screen for fillers and reviewers**: it fails the thresholds there and its protection is our code on a moving library.

### 3.6 Questions for the owner that the numbers raise

1. May a fixed text be longer than 1,000 characters (the real supplier form has one of 1,172)? Suggested 5,000 for a fixed text, 1,000 for an input cell.
2. How wide should the palette be (36 names cover 12 of 17 distinct fills of the two forms within a small colour distance)?
3. Upload: a choice of worksheet, or every visible worksheet (the second needs several sheets per template)?
4. Does any deployment of yours set a strict style policy (the 5 Univer exceptions)?
5. Should an author editor be accessible to a screen reader (decides SH6)?

## 4. What was NOT run (said plainly)

- **No product test of any kind**: no unit, architecture, integration or browser test of the platform, no Maven verify, no `npm run build` of `platform-web` (no product code changed). The only Java compiled and run is the throwaway project and its harness.
- **The written sample file was not opened in a spreadsheet application** (POI reopened it: 15 of 15 checks). That is the owner's step (manual test A).
- **No screen reader, no real touch device, no Firefox or Safari**: a Chromium-based browser (version 155) on this laptop only. Univer's Tab path to the toolbar and its bold shortcut were not checked by hand.
- **Find and replace** (its API replaced nothing even without a guard; the dialog was not driven), a **real mouse drag of the fill handle**, number-format and other toolbar settings under the guard (reasoned: the filler's screen hides the toolbar).
- The Univer **theme from the tokens**, the **snap of a free colour to the closed palette at edit time**, a **parallel accessible layer** (all sized by reasoning).
- **A Java interpreter of the stored form** was not written: the server side was proved with POI's evaluator (29 of 29 equal to the spreadsheet program's stored value) and the browser side with our own evaluator (29 of 29, 34 of 34); the Java interpreter is the first part of SH2, with POI as its test oracle.
- **The small formula cases were compared with documented spreadsheet behaviour, not run in a spreadsheet program**; the 29 real formulas were compared with the values **the spreadsheet program itself stored**.
- **Fill documents** were not regenerated for the shim (grids and archived bytes were); no real filled example exists yet (counts only will be recorded when one arrives).
- **Memory and time inside the application** (the measures ran in separate processes with a heap cap), **the intended managed instance**, **concurrency of four uploads**.
- **The two real workbooks in the product's own screens**: only the harness drew them; the upload still refuses the supplier workbook.
- The cell-protection run's find-and-replace row is not valid (reported above); a second run of that operation alone (`results/poc1-univer-classes-only.json`) replaced nothing either.

## 5. Commands to run everything again (from `platform/`; the long ones are yours to run)

Prerequisites: JDK 25, Node 24, a Chromium-based browser (`CHROME_PATH` if not in the usual place), the two real workbooks copied to a folder **outside the repository** (call it `$DATA`, with the names `plant.xlsx` and `spm.xlsx`).

```
# the throwaway Java project (POI 6.0.0); jars are copied to target/lib
./mvnw -B -ntp -f spikes/sheet-engine/pom.xml package -DskipTests
cd spikes/sheet-engine
CP="target/classes;target/lib/*"
java -cp "$CP" spike.Inspect $DATA/plant.xlsx $DATA/spm.xlsx                  # WP1.1 counts
java -cp "$CP" spike.FormulaCheck $DATA/plant.xlsx $DATA/spm.xlsx             # WP1.2 evaluator against the spreadsheet program's stored values
java -cp "$CP" spike.WriteSample                                               # WP1.3 writes target/out/poc1-sample-styles-and-formulas.xlsx
java -cp "$CP" spike.Measure gen $DATA/big5mb.xlsx                              # WP1.5 a file just under the 5 MB cap
java -Xmx300m -cp "$CP" spike.Breakdown events $DATA/big5mb.xlsx                # WP1.5 streaming reader, cold (use `model` for the whole-workbook route)
java -cp "$CP" spike.IdFormulaCheck $DATA/plant.xlsx $DATA/spm.xlsx           # WP4
java -cp "$CP" spike.ExportNeutral $DATA/spm.xlsx $DATA/spm.json                # the fixtures for the browser (also plant)
```

The harness that needs the platform's classes (corpus, shim): first `./mvnw -pl platform-app dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=<file>` (the module must have been compiled once,
including its tests), then compile `spikes/sheet-engine/src/harness/java/app/platform/sheets/internal/*.java` with `javac --release 25 -proc:none -d spikes/sheet-engine/target/harness -cp "<target/classes;target/lib/*;platform-app/target/classes;platform-app/target/test-classes;platform-api-contract/src/main/resources;contents of the file>"`
and run `app.platform.sheets.internal.PocHostile <DATA folder>` (WP1.4) and `app.platform.sheets.internal.PocV1Shim <plant.xlsx>` (version 2 shim).

```
cd spikes/sheet-grid
npm ci                                  # (the harness now pins Univer 1.4.0 and three presets; the 1.1.0 results in results/ were measured before)
npm run build
node --test lib/poc1Formula.test.mjs                                        # POC1_DATA=$DATA for the real formulas
POC1_DATA=$DATA node poc1-closed-lists.mjs
POC1_DATA=$DATA node poc1-univer-basic.mjs --runs 5                         # policy, weight, speed, scrolling (about 3 minutes)
POC1_DATA=$DATA node poc1-univer-formulas.mjs
node poc1-univer-classes.mjs --seeds 20 --guards none,events,block,builtin   # the cell-protection experiment (about 17 minutes; four browsers at once; do not use the machine)
POC1_DATA=$DATA node poc1-extras.mjs                                        # accessibility, keyboard, dark, phone, own grid, several sheets
POC1_DATA=$DATA node poc1-serve.mjs                                         # look at the pages by hand (manual testing, part B)
```

---

*Pointer added 2026-11-12: the second proof of concept, `docs/spikes/spreadsheet-fidelity-spike.md` (fidelity, the fill-in model, several sheets, the look, more formulas), builds on this report and does not change it. It corrects one figure here: the closed palette of POC 1 holds 32 names in the code, not 36.*
