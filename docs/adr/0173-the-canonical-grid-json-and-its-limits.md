# ADR-0173: The canonical grid JSON and its limits

- **Status:** Proposed
- **Date:** 2026-10-21
- **Sprint:** FA0 (Functional track, spike; no product code)
- **Related:** [ADR-0070](0070-definitions-are-json-checked-by-a-schema.md) (definitions as validated JSON), [ADR-0083](0083-storage-benchmark-result-gate-g1.md), [ADR-0126](0126-visual-design-system-and-self-hosted-fonts.md), [ADR-0172](0172-the-grid-component-and-the-adapter.md), [ADR-0174](0174-template-versions-and-approval-together.md)
- **Evidence:** `platform/spikes/sheet-grid/` (`schema/`, `pg/`, `results/pg-*.json`)

## Context

A template is a spreadsheet: columns, optionally named rows, merged cells, bold and italic, cells that are fixed text and cells a
filler types into. The product owner decided (2026-10-21): up to **1 MB**, up to **500 rows by 100 columns**, stored sparsely, column
types **text, number, date, picklist, yes/no**. `object_record` cannot hold this (flat values, 256 KB a record, no nested JSON), and the
body must be pinned by approvals and by later fill-ins, so it gets its own table (one row per version, [ADR-0174](0174-template-versions-and-approval-together.md)).
The format is **ours**: a grid library's format never reaches the database or the API ([ADR-0172](0172-the-grid-component-and-the-adapter.md)).

## Decision

### 1. The document (format version 1)

One JSON object, validated by one schema file (`sheet-grid.schema.json`, the pattern of [ADR-0070](0070-definitions-are-json-checked-by-a-schema.md);
in FA3 it moves to `platform-api-contract/src/main/resources/schemas/` and is copied into the OpenAPI document). The spike's file is the starting point and passed its corpus.

| Part | Meaning |
|------|---------|
| `formatVersion` | `1`. A document of another number is refused; a later format is a new reader plus a migration of stored versions, never an in-place change. |
| `dimension` | `1` (columns only: the filler adds the rows) or `2` (the template names the rows). |
| `rowsMode` | `FIXED`, `REPEATABLE`, `FIXED_AND_REPEATABLE`. Dimension 1 is `REPEATABLE`; dimension 2 is `FIXED` or `FIXED_AND_REPEATABLE`. Stored now so the fill-in set needs no format change. |
| `columns[]` | `{id, label, type, required, default, picklist, width}`; `type` is one of the five; a `picklist` column carries `{values: [...]}`. |
| `headerRows[]` | `{id, height}`: extra bands above the body under the column labels (the title over merged columns). Their cells are always fixed text. |
| `rows[]` | `{id, label, height}`: the named body rows (dimension 2 only). |
| `cells` | **Sparse**: `"rowId:columnId": {value, style{bold,italic}, kind}`. Only a cell that holds something or has a style is stored. |
| `merges[]` | `{from, to}`: two cell keys (top left, bottom right), inside one zone (header or body), never overlapping. |

**Stable identifiers.** Rows and columns have identifiers (`[A-Za-z0-9_-]{1,40}`, generated, never typed and never reused inside one template), and everything that points at a
place (cells, merges, later change-request comments, fill-ins) uses them, never a position. Labels may change between versions without breaking anything.

**What a cell is.** The body zone is *input* by default; a stored cell with a value and no `kind` is *fixed* text (a label, an instruction, a limit); header cells are always fixed. So a
2-D template of 50 rows by 10 columns stores only its row labels and fixed text. A merge keeps content on its top left cell only (what the libraries do as well; the adapter drops what a library copies onto covered cells).

### 2. Column types and what a "picklist" refers to

| Type | In a fill-in cell | In the template |
|------|-------------------|-----------------|
| text | a string, at most 1,000 characters, no markup or control characters | fixed text; column default |
| number | **a string of digits** `-?\d{1,38}(\.\d{1,18})?` (the platform's rule that a number travels as the digits typed, [ADR-0129](0129-record-screens-the-members-description-names-and-validation.md)) | default |
| date | `YYYY-MM-DD`, a real date | default |
| picklist | one of the values of the column | the list is typed into the column (up to 100 values of up to 100 characters, no duplicates) |
| yes/no | `true` or `false` | default |

**A picklist column holds its own list, inside the version.** It does not point at a platform picklist. Reasons: a version is immutable and pinned by fills, so the allowed values must be
frozen with it; a platform picklist can change after approval and would change the meaning of old fills; and no dependency graph or change-set machinery is needed for the grid. The **dependent
picklists of FA2 ([ADR-0177](0177-dependent-picklists.md)) belong to the template's header fields** (category and sub-category, which also build the number), not to grid columns: the two are
unrelated. A later release may let a column say "values from this platform picklist, copied into the version at save"; nothing in the format prevents it.

### 3. Limits (settings, with defaults)

`platform.sheets.limits.max-bytes` 1,000,000 (the size of the serialised document, checked **before** it is parsed); `max-rows` 500; `max-columns` 100; `max-header-rows` 10;
`max-cell-chars` 1,000; `max-label-chars` 200; `max-picklist-values` 100; `max-merges` 2,000; `max-depth` 12. A limit that is reached is `VALIDATION_ERROR` naming the limit in fixed words;
nothing is cut. The caps are product caps, not storage limits: a version of 1 MB is stored without trouble (below).

**What fits.** The empty frame of a full 500 x 100 grid (500 row definitions, 100 column definitions, no cell) is **69,789 bytes**. Under the 1 MB cap, **13,679 fixed cells of 20 characters** fit
(27 percent of the 50,000 cells); with fixed cells of 5 characters, 17,551 (35 percent). A completely full grid of text is therefore **refused at the cap**, cleanly: the answer is
"this template is larger than 1,000,000 bytes", and the author splits it. Typical sheets are far below, measured with the spike's generator (row labels in the first column, a title band with two merged cells, 5 percent of the other body cells fixed text; the rest are input cells and are not stored):
**8 columns by 20 rows: 4.0 KB** (26 stored cells); **15 by 60: 12.2 KB**; **30 by 150: 38.6 KB**; **100 by 500: 250 KB** (2,983 stored cells of 50,000); a one-dimensional template of 10 columns: 1.7 KB. The 20 KB document used for "typical" in the table below
is a sheet of about 100 rows by 10 columns with more fixed text than that; the real typical sheet of the business is not known yet (the product owner's real example is still to come: open item B of the business decisions file).

### 4. Validation, and the hostile corpus

Validation is a pipeline of stages; **the first stage with a problem answers alone** (the pattern of [ADR-0091](0091-the-write-pipeline-and-its-order.md)): size and nesting of the raw text, then JSON, then shape (the schema),
then text (no markup, no control characters), then identifiers (unique across rows and columns), then cells (point at a real row and column, header cells fixed, values fit the type), then merges (one zone, ordered, not one cell, no overlap).
Every problem has a stable code, fixed words and a place made **only of identifiers that match the identifier pattern** (anything else is shown as "…"); a message **never repeats a typed value, a label or a key**
(the grid is the organization's text: [ADR-0012](0012-observability.md)).

The corpus (`schema/run-corpus.mjs`) runs 34 cases and all behave as expected: a cell of 10 MB (refused on size in 3 ms, before parsing); a cell of 1,001 characters; nesting 10,000 deep (refused before parsing); not JSON; an unknown part;
format version 2; a cell key that is not `row:column`; a `__proto__` key; an identifier of 41 characters; a number where text belongs; 600 rows; 101 columns; markup in a column label, a row label and a cell; a control character;
a repeated identifier (two columns, and a row against a column); a cell on a row that does not exist; overlapping merges; a merge from header to body; a merge written backwards; a merge of one cell; a merge to a cell that does not exist;
a preset that does not fit its column; an input cell in the header; a list column without values; a list with a value twice; a default that does not fit the type; a repeatable template that names rows. **None of the messages repeated the text** (the script
fails the run if one does). The valid cases (the 8x20 scenario with two merged header cells, one dimension, 60x15, 500x100 at 5 percent fixed cells, "5 < 6" as plain text) are accepted; the 500x100 document validates in about 10 ms.

**Known limit of the spike, carried into FA3:** `JSON.parse` collapses a repeated key; the Java reader must switch on strict duplicate detection (Jackson `STRICT_DUPLICATE_DETECTION`) and the corpus gets a case for a repeated cell key. Markup is *refused*, not escaped, so that every later reader (exports, mails, other front ends) can trust the stored text.

### 5. Storage and the measurements (PostgreSQL 18.6, forced row level security, role `platform_app`, tenant set with the real setting)

One table `sheet_version` (FA3, V059 or later): the base columns and guard, `tenant_id` (RLS enabled and forced, [ADR-0003](0003-rls-defence-in-depth.md)), `owner_object` and `owner_id` (the record the sheet belongs to: the generic module `sheets` knows no cluster word; the cluster's template is the first owner), `version_no`, `status`, `grid jsonb`, `grid_hash bytea`, unique `(tenant_id, owner_id, version_no)`. The spike measured the same shape with the owner column named `template_id`.
The spike made exactly this shape in a throwaway schema (`fa0_spike`, dropped afterwards) and ran it **as the application role**; a reader of another organization saw nothing (checked on every size). Round trip of an empty transaction with the tenant setting: **3.25 ms** p50 (subtract it).

| Document (serialised) | Stored (pglz, the default) | Stored (lz4) | Insert p50 / p95 | Read one p50 / p95 | List of the template's versions, no grid, p50 |
|---|---|---|---|---|---|
| 19.7 KB (typical) | 5.4 KB | 6.9 KB | 5.7 / 6.8 ms | 4.1 / 5.3 ms | 3.1 ms |
| 99.5 KB | 22.1 KB | 31.9 KB | 6.9 / 8.3 ms | 4.9 / 6.8 ms | 2.6 ms |
| 498.8 KB | 96.1 KB | 156.3 KB | 23.6 / 25.1 ms | 14.1 / 17.9 ms | 3.5 ms |
| 988.8 KB (the cap) | 180.9 KB | 307.5 KB | 41.7 / 58.8 ms | 23.3 / 31.0 ms | 3.5 ms |

(n = 40 documents for the two small sizes and 15 for the two large ones, three reads each; a warm cache; the local database runs with default settings, 128 MB of buffers.)

- **TOAST** moves any document over about 2 KB out of the row; PostgreSQL compresses it in place. **A 1 MB grid is stored as 181 KB** (5.5 times smaller) with `pglz`. For this data `pglz` stores 22 to 41 percent less than `lz4` and `lz4` is 14 to 27 percent faster to write: **keep the default (`pglz`)**; the saving matters more than the milliseconds.
- **A history of 50 versions** of one template: 20 KB documents: 1.08 MB serialised, **272 KB stored**; 100 KB documents: 5.4 MB serialised, **1.1 MB stored**. **Listing the versions without the grid takes 3.8 ms; with the grid 12.7 ms (20 KB) and 42 ms (100 KB)**: the grid is not read unless it is selected. **Rule: a list of versions never selects `grid`.** The newest version's grid reads in 4.3 ms (20 KB) and 6.2 ms (100 KB).
- **Saving a draft over and over** (the draft row is updated in place until it is submitted): 100 saves of a 100 KB document, 8.1 ms p50 each; the table (and its TOAST) grew from 6.9 MB to 9.3 MB and did not grow further after `VACUUM`. Dead copies are reused; there is no runaway.
- Nothing was run on a managed instance; the pass holds for the local engine (condition 6 of [ADR-0083](0083-storage-benchmark-result-gate-g1.md) stands).

### 6. Fill-in volume (evidence for the fill-in set; FA0 builds none of it)

**The shape to plan for:** about 1,000 fill-ins a day for one template, about 50 rows each, up to 100 columns, though a sheet rarely fills all of it; about 365,000 fill-ins a year for one template at the top.
**The real filled example has not arrived**, so the sizes below are for **generated** fill-ins: 50 rows, with 10, 20, 50 and 100 columns filled; text from a small vocabulary, pick-list words, numbers as digits, dates, yes/no. Text written by people compresses worse than this, so **treat every size as a lower bound** and re-measure on the real example before the fill-in set is planned.

**Three shapes of the fill document were measured** (30 different documents each, stored in `jsonb`, `pglz`):
A = cells keyed `"rowId:columnId"` (the analysis's shape); B = a list of rows, each with cells keyed by column identifier; C = a list of rows, each a list of values in the template's column order (`null` for an empty cell; the template version the fill is pinned to gives the order).

| 50 rows, columns filled | serialised A / B / C | **stored** A / B / C (pglz) | stored A / B / C (lz4) |
|---|---|---|---|
| 10 (typical) | 16.3 / 13.2 / 6.4 KB | **4.5 / 3.1 / 2.1 KB** | 6.0 / 3.9 / 2.7 KB |
| 20 | 32.6 / 25.0 / 12.7 KB | 8.4 / 5.2 / 3.6 KB | 12.2 / 6.7 / 4.7 KB |
| 50 | 81.3 / 60.2 / 31.4 KB | 20.3 / 11.6 / 8.1 KB | 27.5 / 15.8 / 10.7 KB |
| 100 (the rare full case) | 162.2 / 118.6 / 62.4 KB | 40.1 / 23.1 / 15.7 KB | 54.7 / 25.9 / 20.3 KB |

**Compression:** `pglz` stores **11 to 31 percent less** than `lz4` in every case measured (shape A 26 to 31 percent), and `lz4` writes **8 to 22 percent faster** for a fill-in; PostgreSQL 18 offers no other method for a column. Compressing the document **in the application** (gzip, then `bytea`) gave 2.9 KB for the typical shape A
against 4.5 KB in the database: a better ratio, but it loses the ability to read a cell in SQL and is only worth it for **archived** fills (below). **The shape matters more than the method:** shape C is about 2.1 times smaller than shape A. The cost of C: a fill is read only together with its version and positions are fragile to debug;
B keeps the identifiers readable at 1.4 times the size of C. **Recommended for the fill-in set: B or C, decided with the real example**; the canonical template format does not depend on it.

**What was loaded and read (measured, 1,095,000 typical shape-A fill-ins = three template-years in one table, as the application role under forced row level security):**
heap 148 MB, index 135 MB, TOAST 5,703 MB; stored payload 4,468 bytes a fill and **5,461 bytes a fill in TOAST** (a 22 percent overhead of the chunks), so **about 5.7 KB a fill with its row and index entry**, or **1.95 GB per template-year**. The server-side bulk insert took 59 ms per 1,000 rows (63 seconds for the whole load); one fill inserted through a round trip took 4.8 ms at p50.
The list the screens need, **"the fill-ins of one template, newest first, 50 a page"** (index on `(tenant_id, template_id, created_at desc, id desc)`): first page **4.7 ms p50** without the cells (the empty round trip is 3.25 ms), **12.0 ms** with the 50 documents; **a page 7,000 pages deep by offset 32 ms** (35 ms with cells), **by key (the last row seen) 5.1 ms at any depth**. The rule for the fill-in set: **page by key, not by offset, and list without the cells**.

**Projected (derived by scaling the measured overhead to the stored sizes above; not measured):** per year, in GB on disk, rows plus TOAST plus index, before WAL and backups.

| Fill-ins of 50 rows | 1 template (365,000) | 10 templates | 10 templates in each of 10 organizations (36.5 million) |
|---|---|---|---|
| 10 columns, shape A / B / C | 1.9 / 1.4 / 1.0 GB | 19.5 / 13.9 / 9.8 GB | **195 / 139 / 98 GB** |
| 20 columns, A / B / C | 3.6 / 2.3 / 1.6 | 35.9 / 22.5 / 16.0 | 359 / 225 / 160 |
| 50 columns, A / B / C | 8.5 / 4.9 / 3.5 | 85 / 49 / 35 | 852 / 491 / 348 |
| 100 columns, A / B / C (if every fill were full) | 16.7 / 9.7 / 6.6 | 167 / 97 / 66 | 1,674 / 971 / 662 |

**What the design carries, and what would have to change.** Throughput is not the limit: 100 template-years at 1,000 a day is 100,000 fills a day, about 1.2 a second, against a few milliseconds per insert and lists that stay at a few milliseconds on a table of 1.1 million rows. **Storage and its upkeep are:**
one template-year of typical fills is about 1 to 2 GB; **ten templates in ten organizations is of the order of 100 to 200 GB a year at the typical shape**, and the rare full sheet multiplies it by 8 to 20 (one in ten fills being full roughly doubles the typical figure). The machine class in the transition plan (the benchmark laptop: 14 processors, 7 GB of memory, a large disk) carries the measured case; **this was not run on the intended managed instance**, and the database here runs with default memory settings.
Beyond about **a few hundred GB**, in this order: (1) the shape (B or C); (2) **monthly partitions** by `created_at` (cheaper vacuum, smaller indexes per partition, a unit for archiving); (3) **move fills older than a chosen age to the object store** as compressed documents and keep only the header row and a pointer (never deleted: a decided rule), read back on demand;
(4) a **typed projection table** for the columns flagged reportable, copied in the same transaction (never an index on the JSON: [ADR-0083](0083-storage-benchmark-result-gate-g1.md), [ADR-0101](0101-the-index-policy-and-the-slots.md)); (5) a per-cell patch endpoint, only if whole-document writes of the full sheet become a problem. None of these needs a change of the template format.

## Consequences

- One schema file, one validator class (`SheetGridValidator`), one limits class; the adapter of [ADR-0172](0172-the-grid-component-and-the-adapter.md) converts to and from the library; the browser validates for the person's convenience only and the server decides again.
- A version row is written whole each time (no per-cell patch endpoint); that is acceptable at these sizes (above) and is revisited for fill-ins.
- The schema's `kind` rule ("fixed if it has a value and no kind") is implicit; the Java model states it in one place and the form view shows it.
- **Not decided here, asked of the product owner later:** whether a column may refer to a platform picklist copied in at save (a later release), and whether a merge may span the header and body (refused now).
