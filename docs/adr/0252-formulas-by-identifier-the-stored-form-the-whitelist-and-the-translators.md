# ADR-0252: Formulas by identifier: the stored form, the whitelist and the two translators

- **Status:** Proposed (proof of concept POC 1, 2026-11-12; nothing in the product is built from it until the product owner accepts it)
- **Date:** 2026-11-12
- **Track:** Functional track, proof of concept (no product code)
- **Related:** [ADR-0251](0251-sheets-format-version-2-and-the-engine.md) (the format and the engine this belongs to), [ADR-0173](0173-the-canonical-grid-json-and-its-limits.md) (permanent identifiers, canonical form), [ADR-0076](0076-the-expression-engine-and-its-language.md) and [ADR-0081](0081-security-review-of-the-expression-engine.md) (no text of an organization is run as code; the bounds), [ADR-0012](0012-observability.md)
- **Evidence:** `docs/spikes/spreadsheet-engine-spike.md` (WP1.2, WP4); `spikes/sheet-engine/.../IdFormulas.java`, `Whitelist.java`, `IdFormulaCheck.java`; `spikes/sheet-grid/lib/poc1Formula.mjs` and its test

## Context

The two real forms hold formulas: 7 in the plant workbook (totals over a column) and 22 in the supplier workbook (one per column, all on the first data row, meant to be repeated for every row the
filler adds). Our sheet keeps every place by **permanent identifiers of rows and columns** (ADR-0173), so that notes, fill-ins and change requests survive a row or column being added, moved or
removed. A formula written the way a spreadsheet program writes it (`=SUM(D4:D9)`) points at **positions**, which move.

## Decision

1. **A formula is stored in an id-based form: the text of the formula, with every cell reference replaced by a token that names the column and the row by their identifiers.**
   `@{COL}` is that column **on the formula's own row**; `@{COL,ROW}` is a cell of another row (a named row, a header row); the third part holds the absolute markers `c` and `r`
   (`@{COL,ROW,cr}`, `@{COL,,c}`). A range is two tokens joined by a colon. Functions, operators, numbers and text are kept character for character. Identifiers match
   `[A-Za-z0-9_-]{1,40}`. The form reserves a fourth part, the **sheet key**, for several sheets in one template (ADR-0251, section 6): today it is never written.
2. **Where a formula lives.** A **calculated column** (dimension 1, rows added by the filler) holds one formula for the column; every row the filler adds shows the same stored text drawn at its
   own row, so nothing is copied and no row is ever rewritten. A **named row** may hold a formula in a cell (a total). A formula is never stored in a fill-in: the fill-in holds only the
   values of input cells, and the results are computed from the pinned version of the template.
3. **Two translators, one on each side.** Excel-style to identifiers: a scanner over the text that skips text constants, rewrites each reference and **refuses** a reference outside the
   sheet (a column or row that has no identifier), another sheet, an external workbook, a name, the characters `@ { [ ! '`, and an unclosed text. Identifiers to Excel-style: draws the same
   stored text for a given row; a token that names a column or row that does not exist is refused. Both exist in Java (the server: upload, download, calculation) and in TypeScript (the
   browser: the formula bar). The texts are equal for all 29 real formulas, in both directions, and inserting a row or a column rewrites **no** stored formula (only the drawn text changes).
4. **The gate: a whitelist of token kinds and of functions, applied in this order.** (a) the length of the text (4,000 characters) and the nesting of brackets (32), **before any parser sees
   it** (the parser of the spreadsheet library overflows its stack on a flat sum of about 50,000 terms); (b) a parse by the library's parser (server) or our own (browser); (c) an
   **allowlist of token kinds** (numbers, text, booleans, plain references and ranges on the same sheet, the arithmetic and comparison operators, percent, unary plus and minus,
   brackets) and of functions: `IF`, `MIN`, `MAX`, `SUM`, `ROUND`, `AND`, `OR`, `NOT`, `ISBLANK`. A blocklist of functions let one hostile form through (the intersection operator): the
   allowlist is the rule. Everything else is refused with a stable reason that never repeats the formula. `SUM` is a new function for the expression engine's family.
5. **The server is the one authority for stored results.** A formula is interpreted over its parsed tree and never run as code. The server calculates with a whitelisted evaluator over the
   same tree shape; the browser's evaluator is for showing a result while a person types and never decides. They are tested against each other and against the values the spreadsheet program stored: 29 of 29
   real formulas, including the two cells where the spreadsheet program stored an error, and 34 small cases (blank in arithmetic, number as text, text in arithmetic, divide by zero, error through `IF`,
   a blank guard, `SUM`/`MIN`/`MAX` over blanks and text, `ROUND` of 2.675 and 1.005, `AND`/`OR` with a blank reference, `ISBLANK`, case-insensitive comparison, percent, `-2^2`).
6. **A result is stored with its kind** (number, text, true or false, or an error code) when a version is saved, so a list or a report never calculates; the fill-in's calculated values
   are derived when it is shown and when it is sent, from the version it is pinned to.

## Consequences

- No migration for the form itself: it lives inside the version 2 document (ADR-0251). Version 1 documents have no formulas and are untouched.
- The author sees and types Excel-style text; the identifiers are never shown. Copying a formula from a spreadsheet file, pasting one, filling one down or inserting a row all go through the
  translators; the adapter must **copy the pattern into a row that is inserted** (no engine does it).
- The expression engine (ADR-0076) is **not** reused for sheet formulas as it stands: it has no references and no `SUM`. The sheet evaluator shares its bounds and its rule that nothing is
  ever run as code; whether it later becomes a mode of that engine is a decision for the sprint that builds it.
- **Not decided here:** text joining, dates and the other functions a later release may want (each needs a case in the small-case table on both sides and a line in the refusal corpus), and
  references between sheets (ADR-0251, section 6).

## Amendment (POC 2, 2026-11-12): the larger function set and the growing range

Adds to the decisions above (`docs/spikes/spreadsheet-fidelity-spike.md`, section 2.5). Nothing above is withdrawn.

**A. The set grows from 9 functions to 40** (43 with three that need the owner's decision): arithmetic `ABS MOD INT ROUND ROUNDUP ROUNDDOWN SQRT POWER PRODUCT`; conditional `IF IFERROR AND OR NOT ISNUMBER ISTEXT ISBLANK SUMIF COUNTIF AVERAGEIF IFS SWITCH`; statistical `SUM AVERAGE MEDIAN MODE COUNT COUNTA MIN MAX LARGE SMALL`; casting and text `VALUE TEXT CONCATENATE LEFT RIGHT LEN UPPER TRIM`. The browser evaluator and POI's own evaluator were run on **225 cases** written from the documented behaviour of each function (a blank, text, a number as text, an error, an empty range, a range with text, the edge answers): the browser equals the documented value on all 223 it accepts; **documented = POI = browser on 220**; the 29 real formulas still equal the values the spreadsheet program stored.
- **Out of the set by the rule written first** (a function needs POI and the browser to agree on every case): **`N`** (POI cannot run it) and **`STDEV`, `VAR`, `RANK`** (POI answers `#NUM!` for one value where the documentation says `#DIV/0!`, and 1 for a number that is not in the list where the documentation says `#N/A`). **Recommendation, the owner's to accept:** keep the three with a documented exception: the server's interpreter follows the documented behaviour and is the authority (decision 5 above), POI stays the second opinion on every other input. **`COUNT` is narrowed to cell references and ranges** (a typed constant makes POI count wrongly), and **`TEXT` takes only a listed format as a text constant** (`0`, `0.0`, `0.00`, `0.000`, `0%`, `0.0%`, `0.00%`, `#,##0`, `#,##0.00`).
- **Kept out for ever:** anything that reads the clock, the network, another file or evaluates text (`INDIRECT`, `TODAY`, `NOW`, `RAND`, `RANDBETWEEN`, `OFFSET`, `HYPERLINK`, `WEBSERVICE`, `FILTERXML`, `ENCODEURL`, `EVALUATE`, `CALL`, `INFO`, `CELL`), the intersection and union operators, text joining with `&`, other sheets and other workbooks. The gate (bounds on the text first, the library's parser, an allowlist of token kinds and of functions) refused **20 of the 21 hostile formulas of POC 1** (the 21st, `TEXT(A1,"0")`, is now a permitted form by this decision), **32 of 32 new hostile cases** and accepted 45 of 45 good ones. One detail: the library's parser files the functions that came after the first file format (`IFERROR`, `AVERAGEIF`, `IFS`, `SWITCH`) as a name followed by a call; the gate accepts that pair only when the name is an allowed function written without a sheet.

**B. The growing range.** A range over **exactly every body row** of one column is stored as `@{COL,*}` (the first to the last body row, however many rows the filler adds); `@{COL,ROW}:@{COL,*}` and `@{COL,*}:@{COL,ROW}` are ranges with an open end, written by the editor's own gesture. The translator never guesses: only a range over exactly every body row becomes the token; every other range keeps its two row identifiers. The token is drawn in the spreadsheet style with the rows that exist now (`AVERAGE(B2:B4)` becomes `AVERAGE(B2:B6)` after two rows are added; the stored text does not change) and round-trips to the same stored text at every size. A row flagged as a **total row is not a body row**, so a total is not part of its own range; **a formula in a body row may not name its own column with the token** (refused, `circular`); a reference outside the sheet is still refused. Proved in Java (12 of 12 checks, POI recalculating the drawn text: 5 then 8) and in the browser.

**C. Narrow.** The tokens stay `@{COL}`, `@{COL,ROW}`, `@{COL,ROW,cr}`, `@{COL,*}`; the fourth part, the sheet key, stays reserved for a later release.
