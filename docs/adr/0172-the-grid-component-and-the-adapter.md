# ADR-0172: The grid component, and the adapter that keeps its format out

- **Status:** Proposed
- **Date:** 2026-10-21
- **Sprint:** FA0 (Functional track, spike; no product code)
- **Related:** [ADR-0173](0173-the-canonical-grid-json-and-its-limits.md), [ADR-0126](0126-visual-design-system-and-self-hosted-fonts.md), [ADR-0127](0127-custom-page-scripts-and-the-sandboxed-iframe.md) (why a native page, not the frame), [ADR-0129](0129-record-screens-the-members-description-names-and-validation.md)
- **Evidence:** `platform/spikes/sheet-grid/` (`components/`, `adapters/`, `run-spike.mjs`, `results/browser-full-run.json`, screenshots in `results/`)

## Context

The cluster needs a spreadsheet-like editor for templates: columns and rows, merge and unmerge, bold and italic, **fixed cells versus cells the filler types into**, paste of a block copied from a
spreadsheet application, keyboard use, undo, up to 500 rows by 100 columns. It must follow the design tokens ([ADR-0126](0126-visual-design-system-and-self-hosted-fonts.md)), make no network request, work under a strict content security policy,
be usable with a keyboard and a screen reader, and be replaceable: **the library's format never reaches the database or the API**. Only permissive licences qualify (MIT, Apache 2.0, BSD); a paid tier needed for merge or styling, a licence key, or a
proprietary production licence rejects a candidate. A candidate that cannot draw only the rows and columns on the screen at 500 x 100 is rejected.

## Decision

**Build our own table-based grid** (`platform-web/src/components/sheets/`, FA3), on the one small dependency the prototype used (a virtual-scrolling helper, MIT, 3.14.x), behind the adapter below. No grid library is added.
Univer (Apache 2.0 core) is the **named fallback** if our own grid proves too costly to keep; Fortune-sheet and Jspreadsheet CE are not recommended (reasons below).

### 1. How the candidates were found and judged

Searched by hand in the package registry and each project's licence file, README and release dates (2026-10-21); the two names of the analysis were not taken on trust.

| Candidate | Licence (checked in the files) | Verdict | Why |
|---|---|---|---|
| **Own grid** (prototype) | ours | **chosen** | passes every item below |
| **Univer** (1.1.0) | Apache-2.0 for the core. Its README lists collaboration, history, import and export, print, charts and pivot tables as separate commercial packages; none is needed for a template editor | **fallback** | works, but heavy (below) |
| **Fortune-sheet** (1.0.4) | MIT; last release 2025-11-06 (11 months before this date), 75 open issues | not recommended | canvas: no accessibility; breaks under the strict policy; 2.7 MB added |
| **Jspreadsheet CE** (5.0.4) | an MIT licence file in the package, **no licence field in the package**, and its formula dependency carries "MIT" only in a source comment | **rejected** | does not draw only what is on the screen (55,689 page elements at 500 x 100, 215,726 at 1,000 x 200, 18 long tasks) |
| AG Grid Community | MIT, but range selection and clipboard operations are in its paid edition (its own comparison page) | rejected, not prototyped | paste of a block and a selected range need the paid edition |
| Handsontable | proprietary licence file: commercial use needs a licence agreement | rejected, not prototyped | licence |
| react-datasheet-grid | MIT | rejected, not prototyped | no merged cells (its README has none) |
| Glide Data Grid | MIT | rejected, not prototyped | canvas; peers stop at React 18; no merged cells in its README |
| RevoGrid | MIT | **not evaluated** | its README lists merging but does not say which tier it belongs to; revisit if the own grid becomes costly |

### 2. The checklist, with the evidence (production build of Next.js 16.3.8 and React 19.3.0, a real Chromium-based browser 155.0.8059.85, no mocks)

Candidates ran under a content policy with scripts from the page's own origin, no `eval`, and inline styles allowed (**relaxed**), and again with inline styles refused (**strict**: `style-src 'self'`).

| Item | Own | Univer | Fortune-sheet | Jspreadsheet CE |
|---|---|---|---|---|
| Starts under React 19 and Next.js 16 (client only) | yes | yes | yes | yes |
| Imports where there is no window (server render, build) | yes | yes | yes | yes |
| **Virtual rendering** (page elements at 20x8 / 500x100 / 1,000x200) | 298 / 468 / 469 | 673 constant (canvas) | 797 constant (canvas) | 836 / **55,689 / 215,726** |
| Heap after load (MB), 20x8 / 500x100 / 1,000x200 | 3.0 / 3.5 / 4.6 | 21.1 / 21.9 / 24.0 | 7.1 / 8.0 / 10.2 | 3.3 / 6.4 / 15.4 |
| JS added to a bare page (raw, gzip) | **+53 KB, +17 KB** | +7,001 KB, +1,797 KB | +2,704 KB, +577 KB | +473 KB, +126 KB |
| Ready (ms from navigation, local) 20x8 / 500x100 / 1,000x200 | 350 to 535 / 346 to 613 / 354 | 1,628 / 1,603 / 1,630 | 994 / 755 / 1,000 | 388 / 771 / 1,529 |
| Typing, key to painted frame, p50 / p95 (ms), 500x100 | 10 to 12 / 18 to 19 | 24.5 / 54.8 | 5.1 / 17.5 | 22.5 / 34.1 |
| Scrolling 500x100 (frames over 50 ms during the scroll test) | 0 (one run of five had a single 433 ms frame; three reruns had none) | 0 | 0 | 6 |
| Scrolling 1,000x200 | 0 | 0 | 1 (633 ms) | 9 frames; 18 long tasks, 1.2 s |
| No network request after load | yes | yes | yes | yes |
| Strict policy, violations / still usable | 0 / yes | 5 / grid shown | 1 / **grid area collapses** | 21 (with the toolbar) / yes |
| Needs `eval` | no | no | no | no |
| Dark mode through the tokens (mean brightness of the grid, light / dark) | **0.99 / 0.11** | 0.96 / 0.80 (its own theme, partly) | 0.99 / 0.99 | 0.99 / 0.99 |
| Accessibility tree: grid, rows, cells | **1 grid, 23 rows, 170 cells, 8 column headers**, one tab stop, visible focus ring | none (canvas), 13 buttons | none (canvas), 38 buttons | table cells (377), **no grid role, not reachable by Tab** |
| Merge and unmerge | yes (toolbar) | yes (toolbar; unmerge in the menu) | yes (toolbar button; unmerging a merged cell needs the whole merged area selected) | yes (toolbar button opens a **native confirm dialog**) |
| Bold, italic | shortcuts and toolbar | shortcuts and toolbar | bold shortcut and both toolbar buttons; **the italic shortcut does nothing** | toolbar bold only; **no italic, no shortcut** |
| Typing, arrows, Enter | yes | yes | yes | yes |
| Paste a block from a spreadsheet application, one undo step | yes | yes | yes | yes |
| Fixed cells cannot be typed into | yes (built in) | yes, by a cancelable event (we wire it) | yes, by a hook (we wire it) | yes, per cell (we wire it) |
| Pasting across fixed and input cells fills only the input cells | **yes** | not with the event we wired; its paste event can only be cancelled as a whole (its documentation) | not with the hook we wired (the paste went through) | yes |
| Typed values checked against the column type | yes (number refuses letters, list is a select, yes/no toggles) | no (a separate package, not used) | no | no |
| The adapter reads our JSON back unchanged | n/a (it is our format) | yes | yes | yes, after the adapter ignores the style it copies onto merged cells |
| Licence | ours | Apache-2.0 (core) | MIT | MIT file, no field |

Notes that matter: Fortune-sheet leaks a second language into its labels (an `aria-label` reads "Merge cells" followed by the same words in Chinese). Univer converts a typed `12.5` to a number by itself and keeps a style id per cell; the adapter had to cope (round-trip tests in `adapters/adapters.test.mjs`, 4 sizes, all three libraries).
Univer's keyboard paths needed a pause after a programmatic selection before keys register; with that pause every check passed. A first version of the script blamed the libraries for what was the script's pace; the table above is the corrected run.

### 3. What a person will not be able to do in our grid

Draw borders, change colours, fonts or alignment, resize a column by dragging (the form view sets the width), freeze panes, use formulas, use more than one sheet, drag the fill handle, find and replace, import or export a spreadsheet file, insert images, or use rich text.
All of these are outside the decided scope (merge, bold, italic, fixed versus input, five column types). **Open in the prototype, to be closed in FA3 with tests:** input through an input-method editor (the prototype starts an edit on a printable key, which composition input does not produce), column width by drag, a double tap measured on a real device (the phone run used an emulated touch screen: the page fits a 390 px width, a tap selects, a double click opens the editor), a screen-reader pass with a real reader (the roles, names and a live message are present; no reader was run), and high-contrast mode.
**On a phone the grid is for reading and light edits; the form view is the way to build a template** (recommended: the grid is read-only below 40 rem and says so).

### 4. The adapter, and replacing the component later

The page talks to **one interface**, `SheetGrid` (FA3): `{ document, mode: "author" | "fill", onChange(patch), readOnly }` with our canonical JSON in and the same JSON out. All edits are **patches with an inverse** (`gridOps`: `applyPatch`, `typeInto`, `toggleStyle`, `mergeRect`, `unmergeAt`, `pasteBlock`),
so undo, redo, the history of an edit and the leak rules are the page's, not the component's. A different component is a new file that implements the same interface and a pair of pure functions (`toLibrary`, `fromLibrary`); the prototype contains three of them
(`adapters/univer.mjs`, `fortune.mjs`, `jspreadsheet.mjs`), each with a round-trip test for 20x8, 60x15, 500x100 and a one-dimensional template. The canonical format, the schema, the validator and the server never change.

## Consequences

- We own about 800 lines of grid code and its tests; in exchange: 17 KB of gzip, real accessibility roles, the tokens and dark mode, no policy exceptions, and cell rules (fixed, typed, paste) that are ours and tested.
- Cost to carry into FA3: the model tests (`gridOps.test.mjs` is the start), a browser script kept with the code, the input-method and touch work above, and the screen-reader pass.
- If the cost grows, the order to try is Univer (behind the same adapter, with `style-src` loosened for it and its weight accepted) and then re-checking RevoGrid.
- No dependency is added by this ADR except the virtual-scrolling helper (MIT, the one the prototype used), whose reason is the one above; versions are pinned in `package.json` with the lock file as the rules say.
