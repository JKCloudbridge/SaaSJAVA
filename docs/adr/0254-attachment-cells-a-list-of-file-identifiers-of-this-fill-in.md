# ADR-0254: Attachment cells: a list of file identifiers of this fill-in

- **Status:** Accepted on 2026-11-12 for the cell type `attachment` and its place in the format (built in SH1A as a field of a stated input cell); the lifecycle of the files, the removal callback and the check at Submit were built in SH5 and are Accepted (see "Built in SH5" at the end; proof of concept POC 2, 2026-11-12)
- **Date:** 2026-11-12
- **Track:** Functional track, proof of concept (no product code, no migration, no change to the API or the generated documents)
- **Related:** [ADR-0251](0251-sheets-format-version-2-and-the-engine.md) (format version 2, the cell types), [ADR-0192](0192-fill-ins-the-header-record-the-cell-table-and-review-by-the-approval-engine.md) and [ADR-0193](0193-fill-ins-the-rest-attachments-reportable-columns-the-report-and-the-archive.md) (fill-ins, files against the whole fill-in, the rule of the lifecycle), [ADR-0120](0120-files-attached-to-records.md) and [ADR-0121](0121-the-object-store-cleanup-and-health.md) (files on records, no signed address, every download an attachment), [ADR-0115](0115-the-record-access-model-and-how-it-reaches-a-statement.md) (who sees a record), [ADR-0012](0012-observability.md) (what is never logged)
- **Evidence:** `docs/spikes/spreadsheet-fidelity-spike.md` section 2.1 (the 18 embedded-file places of the real plant form), section 2.2 (`PocFill3`: 300 good and 35 hostile fill-ins, the lifecycle); `spikes/sheet-engine/src/harness/.../PocFill3.java`

## Context

The plant activity form has places where the person who fills it attaches a file ("Please attach ... in below table"): 18 cells in which the original workbook holds an embedded file shown as an icon. ADR-0193 put files against the **whole fill-in** (the files of its header record) because nothing then asked for a file on a cell. Now the form asks for "attach here". Decided direction (owner, 2026-11-12): the files are in the platform's object store (an S3-compatible bucket in production, the local one on a laptop), **bytes stream through the application, no signed address, no address stored in the cell** (ADR-0120); up to 5 files per cell; a required flag per cell; the downloaded spreadsheet shows "(n attachments)" and never a file name.

## Decision

1. **An attachment cell is an input cell whose type is `attachment`** (ADR-0251: a cell may state its own type; a column of the type is not needed). It may state `maxFiles` (1 to 5, default 5) and `required`. It is an input cell like any other: a template author sets the type on a cell or on a selected block; the reader of an uploaded workbook **never guesses it from text**.
2. **The value of the cell is a list of file identifiers** (`["fileId", ...]`) in the fill-in's cells document (fill format 3). Each identifier must be **one of the files of this fill-in's header record** (ADR-0120, ADR-0193): the validator checks only that, and that it is not repeated and that the list is within the cell's limit. It checks **nothing about the bytes**. An identifier is shaped like any identifier (`[A-Za-z0-9_-]{1,40}`); anything else is refused before it is compared.
3. **The files stay what they are.** They are the files of the header record, kept for good with it (the header object is "kept"), served only through the application, each download an attachment, the right re-checked on every download. The cell grants nothing: a person who cannot read the fill-in cannot read the file. **No address and no file name is stored in the cell.**
4. **The lifecycle** (one rule, asked in `FillFileRules` and the cell save, never a second rule):

| Moment | Who may add or remove a file | What happens to the cell | A required attachment cell |
|---|---|---|---|
| Draft or returned | the person who started it | adding: the same save names the file in the cell; **removing a file removes its identifier from every cell in the same transaction** | not asked at save |
| Submit | nobody | none | needs **at least one file that still exists** now on the header; the refusal says which cell in fixed words |
| Sent or approved | only a person holding the ability that overrides the lock of an approval: a counted, audited correction (unchanged from ADR-0193) | the correction removes the identifier through the same counted door; it **cannot leave a required attachment cell empty** | already satisfied; the refusal says so in fixed words |
| Archived | nobody | read only; the archive keeps the identifiers and the files stay on the header | |

   A new seam makes the removal clear the cell: `RecordFileRules` (shared kernel, called by the file service) gains an "after a removal" callback implemented by the cluster, which asks the sheets module (`FillLifecycle`) to drop the identifier with the same guards as a save or a correction. A file that is removed while no one can edit the cells (archived) is refused at the rule already.
5. **The report, the archive, the download.** An attachment cell is **not a reportable column** (reportable kinds stay text, number, date, yes/no, list; "has an attachment" may be added later as its own choice). The archive moves the cells document as it is (the identifiers) and the files stay where they are. The downloaded spreadsheet shows the text **"(n attachments)"** in the cell (n is a number; an empty cell stays empty) and never a file name; a spreadsheet written from a template (not a fill-in) leaves the cell empty. The register page and a held copy of a template have no attachment cells: they hold no values of a filler.
6. **Never logged.** The identifiers of files, the names, the count of files of a person and the content are never in a log, an audit record, an event or a metric tag; the audit of a correction or a file act keeps what ADR-0193 keeps (fill-in, act, status, person), never a name.
7. **Limits.** At most 5 identifiers per cell (the cell may lower it); the files of a fill-in keep the limits of ADR-0120 (25 MiB a file, 20 files a record).

## Consequences

- New: the cell type `attachment` and its validator stage (ADR-0251 amendment); the callback on `RecordFileRules` and its implementation in the cluster; a check at Submit; the "(n attachments)" text in the writer. No new table, no migration for this decision (V091 is for several sheets).
- **Changed:** `FillValidator` (format 3), `FillStructure` (the effective type of a cell), `CellValues` (the type), `FillFileRules`, `FileService`, `RecordFileRules`, `FillApprovalHooks` (Submit), `XlsxWriter`; the web fill model and form. The complete list by search of the code is in the report (section 2.2).
- **Proved in the prototype:** 300 of 300 generated fill-ins accepted, 35 of 35 hostile fill-ins refused with a stable code and no repeated text (an attachment given text, a file of another fill-in, the same file twice, six files, an identifier that is a path, a list inside a list, a file in a cell that is not an input cell, ...), the plant form with 52 cells of mixed kinds, and "removing a file makes the four required attachment cells of the form missing again at Submit". **Not proved:** the lifecycle against a real database and object store (the local store did not answer in the proof).
- **Not decided here:** a "has an attachment" report column; scanning files for malware (a deployment choice, ADR-0120); a preview of a file; several files with names shown in the cell (the owner chose "(n attachments)").

## Built in SH5 (2026-11-14, Functional track, sheet engine part 6)

- **The lifecycle table is the rule and is built.** While a fill-in is a draft or returned, the person who started it adds a file
  (bytes streamed through the application to the header record's files, no address and no file name in the cell) and names it in
  the cell by the same save; a cell holds at most `maxFiles` (default 5, at most 5) identifiers, none repeated.
- **A cell names only files of its own header**: at every save each identifier is asked of the new shared-kernel contract
  `RecordFileDirectory` (implemented by the data module); an identifier that is not an active file of this fill-in's header is
  refused with `file_unknown`, in words that never repeat it.
- **Removing a file takes its identifier out of every cell in the same transaction**: `RecordFileRules.removed(change)` is called by
  the file service after the removal; the cluster's `FillFileRules` implements it through `FillLifecycle.forgetFile` (the filler's
  own change in a draft or a returned fill-in, the counted correction in a sent or approved one).
- **Sent or approved:** only the counted correction; it cannot take the last file from a required attachment cell
  (`FillLifecycle.emptiesRequiredAttachment`, asked before the removal). Archived: nobody.
- **At Submit** a required attachment cell needs at least one file that still exists; the refusal names the cell by identifiers.
- **Report and archive:** an attachment cell is not reportable; the archive moves the identifiers and the files stay in the store.
- **Not built:** a "has an attachment" report column, a malware scan, a preview of a file, the names of the files in the cell
  (the owner chose "(n attachments)"). The decisions around it are in [ADR-0258](0258-fill-format-three-the-reviewers-window-and-the-calculation-of-a-fill-in.md).
