# ADR-0256: Drawing the look: white paper, the route of a free colour, fixed panes and the hidden note

- **Status:** Accepted by the product owner on 2026-11-13 (answers "go" to the plan of SH4, whose seven questions all recommended yes). **Built by SH4** (see "Built in SH4" below and the note at the end of ADR-0251).
- **Date:** 2026-11-13
- **Track:** Functional track, sheet engine, part 5 (frontend only; no migration, no change to the API or the stored format)
- **Related:** [ADR-0251](0251-sheets-format-version-2-and-the-engine.md) (section 5: our own grid is the screen for view, print, fill and review), [ADR-0172](0172-the-grid-component-and-the-adapter.md), [ADR-0186](0186-the-sheets-module-versions-the-owner-seam-and-the-request-size.md), [ADR-0126](0126-visual-design-system-and-self-hosted-fonts.md) (tokens), [ADR-0253](0253-workbook-files-through-a-library-reading-by-stream-guards-first-and-what-it-reverses.md) (the file out, whose number-format codes the screen must agree with), [ADR-0252](0252-formulas-by-identifier-the-stored-form-the-whitelist-and-the-translators.md), [ADR-0255](0255-the-workbook-reader-the-look-it-keeps-conditional-colours-and-the-choice-of-worksheet.md) (the conditions), [ADR-0082](0082-storage-benchmark-specification-gate-g1.md) (thresholds written before a number is seen)

## Context

SH1A stored the look of a cell in format version 2 (font key, size, text colour, fill, wrap, vertical alignment, four borders, number format), the frozen pane, the page set-up and the conditional colours; SH1B reads them from a workbook; SH2 stores formulas; SH3 writes all of it into the download. The grid still drew text, four named sizes and four theme colours. The owner's two real forms must be seen in the software as they look in the spreadsheet program: colours, merged areas, borders, row heights, Yes/No cells, frozen rows, conditional colours. The risks are the ones the proof of concept named: a colour from a file reaching a style unchecked, the strict content policy, the dark theme turning a stated colour into something else, the focus ring lost over a dark fill, fifty thousand styles made at once, the virtual scrolling disturbing a fixed row, and a number shown differently from the file.

## Decisions

### 1. A sheet in format version 2 is drawn on white paper in both themes

Inside the grid the colour roles are redefined by a class (`sheet-paper`) to the paper values (tokens `--sheet-paper-*`), so the colours an author chose are never turned into something else; the rest of the screen stays dark. A sheet in format version 1 is drawn exactly as before (the class and every other new part are absent; a test compares the class names cell by cell). Rejected: remapping a stated colour for the dark theme (it would change the meaning of a coloured form, and a status colour such as red on yellow would be lost).

### 2. Exact colours are never adjusted; only a missing text colour gets an ink

A fill with no stated text colour (neither in the style nor in the look) gets a black or white ink, whichever reads better by the contrast ratio (black on a mid grey reaches 4.58 to 1 at worst, which is why the dark ink is black). A stated colour is never touched, even when it reads badly; that is the author's form. A condition that paints a fill but no colour lets the cell's own colour stand.

### 3. The route of a free colour (the answer to the question of the strict policy)

A colour, a size and a font reach the page by one narrow road, `lib/sheets/look/`:

- **A colour** is read by `readLook` only if it matches `^#[0-9a-fA-F]{6}$` (the schema's own pattern; anything else is dropped and the cell is drawn without that part), lower-cased, and handed to the cell as the value of a **custom property set through the style object model** (`--sheet-fill`, `--sheet-text`) that two fixed classes read. It is never a literal in source, never markup built from text, never a style element. `tokens.test.ts` still keeps literal colours out of every source file.
- **A font** is one of the sixteen keys, drawn by a class whose font stack is written once in the stylesheet (system fonts; nothing is downloaded and the platform ships none).
- **A size** is a whole number from 8 to 28 in a custom property that one class multiplies by one point token.
- **A border** is one of 24 classes (four sides by six styles); the ink of a border is a token; a border has no colour of its own in the format.
- **The description of a look** (`describe`) is memoized by look and borders, so a sheet of fifty thousand cells makes a few dozen descriptions (threshold T6: at most 2,000).
- **What the application sends:** no content policy at all today. The framework configuration sets only `nosniff`, frame denial and the referrer policy, and the API sets a policy only on file downloads. The strict policy (`style-src 'self'`) exists in the browser script, which proves the grid and the print view draw with no violation. The grid sets `left`, `width` and `height` through the same object model, which a strict policy allows. The one exception that was there, the `@page` rule of the print view written as a style element, now goes in as a stylesheet made by script (the element stays only for a browser that cannot, and for the unit tests).

### 4. Borders: two neighbours draw one line

A cell draws its right and bottom edge, resolved with what its neighbour states on the facing side (the stronger wins), and its top and left only at the outer edge of the sheet. A merged area draws its own edge: the strongest border stated along that edge by the cells of the area (covered cells may carry the area's borders) and by the cells just outside it. Where a cell states no border the faint grid line of the grid stays, so the sheet remains readable; in print only stated borders are drawn. A cell a person types into that states no look at all has a thin line round it, on the screen and in print, as the file out draws it (SH3).

### 5. Number formats: decimal digits, one file of cases

The ten keys format a **number** (a formula's answer of kind number, a number typed into a number column or a cell whose format formats numbers). Rounding is half away from zero on the decimal digits the server gives (at most fifteen significant), never on a binary value, so 2.675 is 2.68 as the spreadsheet program shows it; a number that rounds to zero keeps its minus sign; accounting writes a negative in brackets and zero as a dash; the locale is fixed (dot and comma). A date is text in a sheet, so the two date keys show the value as it is. `look-cases.txt` holds the cases from the documented behaviour of the format codes the file out writes (`NumberFormats.codeOf`); the TypeScript tests and the Java tests both read it, and the Java test holds the codes to the library's own formatter as an independent witness, listing the lines where the library rounds the binary value instead.

### 6. Fixed panes are sticky layers; what is fixed can be less than what is stated

Fixed rows are sticky rows in the flow (their place is where they are drawn); the fixed columns of every row ride in a layer of no size that sticks to the left edge, so a row stays one `row` for a screen reader and the column and row indices stay right. The fixed rows and columns are always drawn; the virtual window holds the rest, and scrolling to a cell leaves room for the fixed part. The stated freeze is reduced in two cases: a line is moved up or left to the nearest place **no merged area crosses** (a half-fixed area would be drawn half moving), and the fixed part never takes more than **three fifths** of the view (otherwise nothing is left to scroll). A fixed row or column is never scrolled to.

### 7. Conditional colours are display only, and carry a hidden note

A rule is judged in the browser over the **displayed number** (an answer of kind number, or a typed number). Text, a blank and an error never meet a comparison. The first rule that gives a fill, and the first that gives a text colour, win in the stored order. The server stays the authority for values, and nothing in a rule is run. Colour alone must not carry meaning, so a coloured cell also carries a visually hidden note in fixed words: "highlighted: below 5", "at most", "equal to", "not equal to", "at least", "above", "between 1 and 5", "outside 1 to 5" (the number is the one the author stated).

### 8. Kinds of cells

Yes and No are shown as the words (a tick is never the only sign), in view and in print. A number cell is right-aligned unless an alignment is stated (the format cannot tell "stated left" from "not stated", so a left alignment that was stated looks like none: a known difference). An attachment cell shows a quiet "(no attachments yet)", "(1 attachment)" or "(n attachments)". In the author's grid a yes/no or picklist input cell carries a quiet down-arrow mark and a hidden "List: …" of at most five values. An input cell is marked by a small two-tone square (dark with a light edge) that shows over any fill, and still by the hidden words "Input cell". The controls that edit these kinds in a fill-in are SH5.

### 9. Selection and focus over any fill

The selection is a translucent wash laid over the cell (never a background that would hide the fill); the active cell has a dark line inside a light one; the grid's own focus ring is a light line with a dark one outside it. Two tones are chosen so one of them reads at 3 to 1 or better over any fill (a test checks the tokens over every grey; the browser script checks the computed colours over a dark fill, a light fill and a white cell). A user of forced colours gets the system's own.

### 10. Print

The print view draws the same look on its white page: the same classes and properties, the sheet's own `fitToWidth` and `repeatHeaderRows` (a sheet whose header rows do not repeat shows them once at the top), the server's paper and orientation as before, the conditions applied to the same answers the grid shows (the server's when the document is the saved one, the browser's while an author types), and the colours forced to print. The browser's own page breaks may differ from the spreadsheet program's. The print view of a fill-in keeps the browser's width rule until SH5.

### 11. What is left out

The author editor that sets a look by hand and applies a cell type (SH6); the fill-in controls for the new kinds, reviewer columns and attachments (SH5); several worksheets and the tab bar (SH7: a template holds one sheet and the writer already takes a list); fonts shipped with the platform; images and charts; a stored zoom; real check boxes; text that runs into empty neighbours (it is clipped at the cell, without an ellipsis, in a sheet with a look); a screen-reader pass with a real reader (the owner does it).

## Consequences

- The look is a pure function of the stored cell, memoized, with its own tests; a component only applies it. A new part of the look is a case in the shared file, a key in the lists (the test that holds the TypeScript lists to the schema and the stylesheet fails until all three agree) and a class.
- No server change. The generated client and the OpenAPI document are untouched.
- A sheet in format version 2 shows on the screen on white paper even in the dark theme; this is deliberate and documented in the manual steps.
- Two known differences from the spreadsheet program are accepted and listed for the owner's comparison: text does not run into empty neighbours, and a number whose alignment was stated as left is right-aligned.

## Built in SH4 (2026-11-13)

Built as written. Where the build adds to the text above: the input-cell mark and the thin lines of a cell with no look apply to the blank sample rows of a sheet where the filler adds the rows; `answerSet` (formula engine) and the server's list of versions now carry the kind of each answer, which is how a number is told from a text; the browser script `browser-checks/sheet-look.mjs` holds the thresholds T1 to T12 written in the plan before any number was seen. The numbers are in `Sprint SH4.md`.
