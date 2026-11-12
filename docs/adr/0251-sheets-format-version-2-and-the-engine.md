# ADR-0251: Sheets: format version 2 and the engine

- **Status:** Proposed (proof of concept POC 1, 2026-11-12; nothing in the product is built from it until the product owner accepts it)
- **Date:** 2026-11-12
- **Track:** Functional track, proof of concept (no product code, no migration, no change to the API or the generated documents)
- **Related:** [ADR-0172](0172-the-grid-component-and-the-adapter.md) (the grid and the adapter: this one is the "named fallback" it left open), [ADR-0173](0173-the-canonical-grid-json-and-its-limits.md) (the format, identifiers, limits), [ADR-0186](0186-the-sheets-module-versions-the-owner-seam-and-the-request-size.md) (versions, the canonical text and hash), [ADR-0192](0192-fill-ins-the-header-record-the-cell-table-and-review-by-the-approval-engine.md) and [ADR-0193](0193-fill-ins-the-rest-attachments-reportable-columns-the-report-and-the-archive.md) (fill-ins), [ADR-0195](0195-upload-of-a-spreadsheet-into-a-sheet.md), [ADR-0201](0201-view-and-download-of-a-template-sheet-view-only-the-footer-row-and-revisions.md), [ADR-0250](0250-a-spreadsheet-comes-in-as-it-looks-borders-blank-cells-and-the-title-row.md), [ADR-0252](0252-formulas-by-identifier-the-stored-form-the-whitelist-and-the-translators.md), [ADR-0253](0253-workbook-files-through-a-library-reading-by-stream-guards-first-and-what-it-reverses.md), [ADR-0126](0126-visual-design-system-and-self-hosted-fonts.md), [ADR-0082](0082-storage-benchmark-specification-gate-g1.md) (thresholds written before a number is seen)
- **Evidence:** `docs/spikes/spreadsheet-engine-spike.md` (every number below); `spikes/sheet-engine/`, `spikes/sheet-grid/`

## Context

Sheets are the heart of Document Management (templates, revisions, fill-ins, change requests) and are about to be the heart of Management Core (plant activity forms filled monthly, reviewed and
scored) and Supplier Management (supplier rows with calculated ratings). Two real workbooks were read. The sheet we keep today holds five column types, header rows, merges, bold, italic,
underline, alignment, four named sizes, four named text colours, fixed text against input cells, widths and heights. The real forms need **fonts (2 and 6 families, 8 to 26 pt), fills, borders,
number formats, frozen panes, conditional colours, formulas (7 and 22), several worksheets, and some cells filled by the plant person and some by a reviewer.** The upload keeps a formula
only as its last value and reads the first worksheet only; for the supplier workbook the first worksheet is a note and the file is refused for a 1,172-character text. The gap is in the **stored
format and in the reader and the writer**, not only in the screen. The question: how to reach the fidelity **without breaking anything built**, given that authors build and edit formulas in the
browser (answer of 2026-11-12), that fonts, sizes and colours come from **closed lists**, that macros are not wanted, and that a template and a fill-in are one sheet per record for now.

## Decision

### 1. Format version 2 is a list of sheets; version 1 is never rewritten

`{"formatVersion":2,"sheets":[{"key":"main", ...}]}`. The list is there from the first release and holds **exactly one sheet** (the first release keeps one sheet per template record); a later release
raises the limit, which is then a limit and not a format change (section 6). A version 1 document is **read by the unchanged version 1 path** and its canonical text and SHA-256 are what they always were:
the reader dispatches on `formatVersion`, and a stored version is never rewritten. A draft is written as version 2 by the new editor (a version 1 version opened in it is converted on its first
save: the conversion is lossless, section 7). Fill-ins pinned to a version 1 template keep working because the fill-in format (shape B, ADR-0192) is unchanged and `FillStructure` reads whichever
version the pin names. **Proved:** 605 of 605 documents (generated templates of both dimensions, two sample forms, the owner's plant workbook through the upload) keep their canonical text and hash
through the dispatch, read as "version 2, one sheet, key main" and written back give the same bytes and the same hash, and an archived copy keeps its hash; the unchanged version 1 validator
refuses a version 2 document, so none can be stored by accident.

### 2. What a sheet adds (all optional, all closed)

Per cell: a **font family** from four names (SANS, SERIF, DISPLAY, MONO; they cover all 7 distinct families the two real forms use), a **size** from the whole points 8 to 28 (covers every size used, 13, 15 and 26
included), bold, italic, underline, a **text colour** and a **fill** named from a closed palette (neutrals and nine hues in three strengths, 36 names; a name is a hue mixed with the surface token, so
dark mode follows the design tokens), four **borders** each of thin, medium, thick, dotted, dashed or double, a **number format** from a closed list of ten kinds (general, whole, two decimals,
percent, percent with two decimals, thousands, thousands with decimals, accounting, month-year, short date), horizontal and vertical alignment, wrap. Per sheet: frozen rows and columns, widths and
heights (as today), conditional colours (a cell value against a limit, painting from the palette: at most 50 rules), a print set-up (paper, orientation, fit to width, repeated rows), and a cell
**class**: *fixed*, *filler* (today's input) or *reviewer* (new). A formula is stored in the id-based form of ADR-0252. **No free colour, size or font name is ever stored**: so the strict content
policy holds, a design change moves every template at once, and the file is always writable. What a real workbook holds beyond the lists is **mapped to the nearest name and counted in the notes** of
the upload (the palette of 36 names covers 12 of the 17 distinct fills of the two real forms within a colour distance of 30; the rest take the nearest name: a wider palette is a product
decision, section 8).

### 3. The cell classes are enforced on the server; the browser only presents

Who may change which cell is `SheetOwners` and `FillOwners` and the validator (`FillValidator`, the guards of V064 and V069): the *reviewer* class is a cell a person holding an open review task may
change while the fill-in is under review (a counted, audited change without content, the pattern of the existing correction). That needs one migration that replaces the guard function and a
session setting like `platform.fill_correction`. The browser's own checks (section 5) are a convenience: the server decides again on every save and every patch.

### 4. The engine: the file side is ours either way

**Reading and writing workbook files: Apache POI behind our guards (ADR-0253).** **Calculation: a whitelisted interpreter over the stored form (ADR-0252), on the server the one authority, in the
browser for showing a result.** POI's evaluator is kept as the **oracle in tests** (it equals the spreadsheet program's stored value on 29 of 29 real formulas), not as the runtime: it needs a whole workbook object
in memory and its parser overflows its stack on a long sum. Hidden worksheets are **skipped and counted**; form controls, embedded objects, external links and pivot tables are skipped and counted;
macros are refused.

### 5. The screen

**Our own grid, extended, is the screen for view, print, fill and review. The author editor is decided in the session that builds it (SH6 of the proof's backlog), behind the adapter of ADR-0172.** The rule the owner fixed
before the measurements gave this: Univer 1.4.0 fails the thresholds on accessibility (no cell is exposed to a screen reader; Tab never reached its toolbar in the test) and on locked-cell control **unless our own guard is
installed** (its own range protection leaked on 20 of 20 runs; with our guard at the one mutation that writes cells, **0 of 220** changes to a protected cell and permitted edits still worked); the other thresholds pass (policy
with 5 inline-style exceptions under a strict policy, 1.97 MB gzip, ready 1.1 to 2.8 s, typing p95 18 to 49 ms, scrolling). The own grid met every threshold (1 grid, 743 named cells, dark mode through the tokens, 0 policy
exceptions, a formula bar, 592 prototype lines). **The numbers do not change that answer but they add a cost the rule did not foresee:** authors build formulas in the browser, so the author editor is the expensive part of
the own grid (about three sessions) and the part where Univer is strongest, and for an author the locked-cell question does not arise. So: build the read side and the file side first (they are common ground), then choose
for the author editor between building it and **Univer for authors only** (about one and a half sessions; accepting that the editor is a drawing a screen reader cannot read, and the 5 style exceptions on that page if a
strict policy is set). Univer is **not** the screen for fillers and reviewers. Whichever is chosen, the stored format is ours (ADR-0172 stands), a library's format never reaches the database or the API, free colours and fonts
are quantised to the closed lists, and the guard and its experiment (`poc1-univer-classes.mjs`) become a standing test if Univer is used.

### 6. Several sheets in one template: possible later, cheap to keep open

(WP7 of the proof; the detail is in the report.) Version 2 already carries a list of sheets with a key and identifiers are unique **within a sheet**. Later: one extra sprint (about the size of
the sprint that added fill-ins' reportable columns): validator per sheet with a template total of 1 MB, a tab bar on both screens, one workbook with several worksheets in the download, the upload
reading every visible worksheet, fill-ins covering all sheets of a version (`FillDoc` gets a `sheets` map; version 1 reads as the key `main`), `sheet_fill_value` gets a `sheet_key` column with
default `main`, change-request places gain an optional sheet key, and the V064 function that fixes the shape of the first version compares per sheet key. Approval and publish act on the
version, so all sheets move together without a change. **A bad choice today** would be to store sheet-level facts (freeze, print, widths) on the document root, or ids that are unique across a template.

### 7. Version 1 and the existing seams

The matrix of the report (WP5) lists every class, endpoint, table, test fixture and screen that reads or writes the sheet, with the mark *proved*, *reasoned* or *needs a change* for each feature
named by the product owner. Short form: the lifecycle, the approval process, numbering, publish, the audit trail, lists, search, sharing and tenant isolation **do not read a grid** and need no
change; the validator, normalizer, writer, reader, the upload builder, the download, the register page and the browser model need a version 2 branch; the fill-in needs the reviewer class.

### 8. Decisions that are the owner's

The width of the palette (36 names; wider covers more); whether a fixed text may be longer than 1,000 characters (the real supplier form has one of 1,172; the proof suggests 5,000 for a fixed
text and 1,000 for an input cell); the choice of worksheet on upload (the supplier workbook's data sheet is the third visible one); whether the first release lists the nine functions of
ADR-0252 or fewer.

## Consequences

- If accepted, the sheet work is seven expand-only sessions (the report, section 3.3): SH1 format version 2 and the reader, SH2 formulas on the server, SH3 the writer and download, SH4 the screen read side, SH5 the reviewer
  class and fill-ins, SH6 the author editor, SH7 several sheets (optional); about 8 to 9 sessions with the editor built by us, 6 to 7 with Univer for authors. Management Core and Supplier Management start after SH4 and SH5.
- **One new migration is expected** (the reviewer window of V069's guard) and later one for several sheets; version 1 needs none. A new dependency set (13 jars, ADR-0253) enters the scan.
- ADR-0172 stays accepted (the adapter, the format is ours); ADR-0173 is extended by version 2, not changed; ADR-0195 decision 2 and ADR-0201 decision 5 are replaced by ADR-0253; ADR-0250's rules move to the new reader unchanged.
- **Found on the way and independent of this decision** (fix whichever way it goes): the current reader **accepts a duplicate part name** inside the workbook archive and a part named with `..`; it **refuses the supplier workbook**
  for one text of 1,172 characters on its first sheet; and POI, if used, opens a file read-write by default and logs the file addresses of external links.
- **Not decided here:** the width of the palette, the length of a fixed text, the choice of worksheet, the number of functions (section 8); and whether the author editor is accessible to a screen reader (decides SH6).

## Amendment (POC 2, 2026-11-12): what format version 2 must also carry

The second proof of concept (`docs/spikes/spreadsheet-fidelity-spike.md`) tested the owner's stronger requirement: the plant activity form and the supplier workbook must be present in the platform **as they look**, either made in it or uploaded. This amendment **adds** to sections 1 to 8 above; nothing above is withdrawn, and Option A and the decision point at SH6 stand.

**A. Several sheets are required now, not optional.** Plant activity and Action plan are two template records, one sheet each; the supplier workbook is **one record with its four visible tabs** (the three hidden tabs are skipped and counted). Version 2 is a list of sheets with a limit of **8** sheets; each sheet has a `key` (`[a-z0-9][a-z0-9_-]{0,39}`, unique), an optional `title` (the tab's name, at most 31 characters, no markup), a `fillable` flag (an information tab holds no fill-in cells), its own `freeze` (rows, columns) and `print` set-up (paper, orientation, fit to width, repeat header rows), and the whole of the version 1 body. Identifiers are unique **within a sheet**. The bytes limit (1,000,000) is **per template**. The order of the sheets is the order of the tabs and **is part of the hash**; the order of the keys is not. A one-sheet version 2 document without any version 2 part is read from and written back to the version 1 document with identical bytes and hash (**605 of 605**). **Proved** in the prototype: the four visible tabs of the real supplier workbook load as one document of 27 KB; one fill-in covers all tabs of the version it is pinned to (a new fill format 3; fill formats 1 and 2 are not touched).

**B. A cell may state its own type, requirement and class.** In a sheet with named rows an input cell may carry `inputs["rowId:columnId"] = {type, required, class, picklist, maxFiles}`: the type is `text`, `number`, `date`, `picklist`, `boolean` or **`attachment`** (ADR-0254) and overrides its column; `class` is `filler` (default) or `reviewer` (the class is a field of the format; its server enforcement is a later sprint, SH5). A merged input area is one cell. A version 1 validator refuses all of it, so no version 2 document can be stored by accident.

**C. Check boxes are yes/no cells.** The two real forms' 36 check boxes are old-style form controls; our own small reader places each in a cell (36 of 36 exactly) and a Yes box beside a No box in one cell becomes **one yes/no cell** (16 of 16 pairs; 4 single boxes stay single). The value is yes when the Yes box is ticked, no when the No box is, empty when neither (both is refused).

**D. The look is wider.** A colour is a **checked `#rrggbb` value** (stored as written, shape checked on the server and again in the page); a font is a **key of a closed list** of 16 (13 named families, 3 generic fallbacks) and never a name from a file; sizes stay whole points 8 to 28. This replaces the closed palette of section 2 (the code in POC 1 held 32 names, not 36; the measured fit was poor: 4 of 6 and 6 of 12 fills within a colour distance of 10, none of the text colours). The page applies a free colour as a **custom property set through the style object model**, so the strict content policy holds (**0 violations on 18 pages**). Widths and heights follow the file: version 2 allows **8 to 1000 px** for a column and **4 to 600 px** for a row (version 1: 40 and 16 would stop the real forms' narrowest column and shortest rows). Fixed text may be up to **5,000** characters (an input cell 1,000).

**E. Images and links.** The picture in the plant workbook is the icon of its 18 embedded files, not a logo; embedded files are skipped and counted. **A header image is optional**: one PNG or JPEG per sheet, at most 256 KB, stored as a file and referenced by identifier, never inline in the grid; it needs a narrow exception to ADR-0120 (inline display of the two types) and is **not part of the first release** unless the owner asks (about one session). A cell may carry a **`link`**: an `https` address of at most 2,000 characters, shown as the cell's text and opened in a new window with `noopener noreferrer`; every other scheme is refused. The 17 links of the real plant form all point inside the workbook, so they are dropped and counted.

**F. The backlog grows.** SH1 and SH2 become two sessions each, SH3 to SH5 grow by a half to a whole session each, SH6 grows by half a session, and SH7 (several sheets) is **required before Supplier Management**: about **12 to 14 sessions** with the editor built, **10 to 12** with Univer for authors only (the report, section 2.7). Management Core can start after SH5; Supplier Management after SH7.

**Not decided here, the owner's:** the three statistics functions (ADR-0252 amendment), the 32 floating text boxes, narrow columns, 5,000 characters, tab titles (report, "Questions").
