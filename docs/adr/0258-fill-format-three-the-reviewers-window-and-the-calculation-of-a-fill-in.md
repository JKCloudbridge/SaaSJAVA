# ADR-0258: Fill format 3: the reviewer's window at the database, how the filler and the reviewer share the cells, and the calculation of a fill-in

- **Status:** Accepted (the product owner answered the questions of the SH5 prompt on 2026-11-13 with the recommendations and said "go")
- **Date:** 2026-11-14
- **Track:** Functional (sheet engine part 6). One migration (V091, expand only). One shared-kernel contract widened (`RecordFileRules`)
  and one added (`RecordFileDirectory`). No new module edge.
- **Related:** ADR-0192 and ADR-0193 (fill-ins, the counted correction, files, reports, the archive), ADR-0251 (the cell types and
  the class of a cell), ADR-0252 (formulas are computed, not stored), ADR-0254 (attachment cells), ADR-0148 (the record lock),
  ADR-0120 (files on records), ADR-0012 (what is never logged)

## Context

A fill-in on a sheet in format version 2 was refused until now. After SH5 a person who may fill a published template fills it,
the calculated columns work, the file is sent, and a person asked to review fills the cells that are meant for the reviewer.
Four things needed a decision that earlier records did not make.

## Decisions

1. **Fill format 3.** `{"formatVersion":3,"sheets":[{"key","rows":[{"id","cells":{column: text | true | false | [file identifiers]}}]}]}`.
   One sheet for now (the validator refuses any other count with `sheet_count`; nothing in the model forbids several). A fill-in
   on a sheet of format version 2 is always format 3; one on a sheet of format version 1 keeps formats 1 and 2, whose bytes and
   hashes do not change. The checks are in the same fixed order as before: size, nesting (two more levels than format 1, for the
   sheet and the list of files), JSON, shape, the format against the sheet, limits, text, rows, cells. A cell is checked against
   its **effective type**: its own statement in the sheet's `inputs`, else its column's.
2. **The two classes share one document, so the rule is applied to what changed.** The cells document is saved whole by whoever
   saves it. `FillClassRules` compares the new cells with the stored ones: every cell whose value differs must be of the writer's
   own class (`filler` or `reviewer`). The filler cannot change a reviewer cell and a reviewer cannot change a filler cell,
   whichever order they save in, and an unchanged cell is never a change. The counted correction of a person who may override
   the lock (ADR-0192) is a third kind of writer and may change any input cell, as before. A person who holds an open task and
   changes only reviewer cells makes the **reviewer's edit**; a person who changes anything else needs the correction ability.
3. **The reviewer's window at the database (V091).** The cells of a sent fill-in changed only in the correction's window. The
   reviewer's edit is a different act by a different person, so it has its own window: the setting `platform.fill_review_edit`
   (raised for one statement, as the correction's is), a new counter `sheet_fill.review_edits` that must rise by exactly one with
   the change of the cells, only while the status is `SUBMITTED`. The two windows cannot be open together, so one write is never
   two acts; a decided fill-in is never edited by a reviewer; the counter never moves with a change of status, the archive move
   or without a change of the cells. The row version rises as for every change, so a filler's stale save over a reviewer's edit
   is refused as a conflict and nothing is lost silently. `SheetFillGuardIT` drives all of it as the application role and as the
   owner. The reviewer's edit does not return the fill-in and does not restart the review.
4. **Who may be a reviewer for a save** is the one question already asked for reading: a person with an open task of the pending
   request (`FillOwners.holdsOpenTask`). Such a person may read and save that one fill-in without the right to read the header,
   as with reading (ADR-0146); nothing else of the header opens. The audit record is `sheets.fill.review_edited` with the size,
   counts and the fingerprint, never a cell. **Not done:** the edit is not written into the request's own history (that history
   has no kind for it; adding one is a change to the approval engine and was not part of this decision).
5. **Required values.** The filler is asked, at Submit and never at save, for the required cells of the filler's class; each cell
   states its own `required`, else its column's. A required cell of the reviewer's class is not asked of the filler; nothing
   enforces it yet (the content of the review route is MC2).
6. **Calculation.** Calculated answers are computed by the server on every read (`GET /api/v1/sheet-fills/{object}/{id}/calculation`)
   with `SheetCalculator` from the pinned version and the values of the input cells, and are never stored in the cells document
   (ADR-0252 decision 6). A number is given to the calculator as a number, a file list is never an operand. A **reportable
   calculated column** feeds the report copy (`sheet_fill_value`) with its answer, in the same transaction as the save, the
   correction and the reviewer's edit; an **error answer is not a value** and is left out, so a report never compares an error code
   as if it were data. The browser's own evaluator is only a preview while a person types; the server's answer is what is shown after
   a save.
7. **Attachment cells** follow the lifecycle of ADR-0254. A cell names files by identifier only. At save every identifier must be
   an active file of **this fill-in's header** (asked of `RecordFileDirectory`, never trusted). **Removing a file takes its
   identifier out of every cell in the same transaction**: `RecordFileRules` gains `removed(change)`, which the file service calls
   after the removal and the cluster implements by asking `FillLifecycle.forgetFile` (the filler's own change in a draft or a
   returned fill-in; the counted correction in a sent or approved one). A person who may correct cannot take the last file from a
   required attachment cell (`emptiesRequiredAttachment`, asked before the removal). At **Submit** a required attachment cell needs
   at least one file that still exists, whatever the cell still says. An attachment cell is not reportable.

## Consequences

- One migration, expand only; `corrections` and `review_edits` are counted apart so an audit can tell a correction from a review.
- `RecordFileRules.Change` carries the file identifier for a removal (a second constructor keeps the old call shape);
  `FillLifecycle` gains two methods, so a new implementation must give them.
- The guard window is the deciding layer for the reviewer's edit as it is for the correction: a bug in the application cannot let a
  reviewer, or anyone, change the cells of a sent fill-in outside one of the two windows.
- The browser decides nothing: `editAs` on the fill-in view says which cells the screen may offer, and every save is decided again.

## Left out on purpose

Reviewers per plant, the monthly fill-in, the score and the 85 percent rule, the CEO step, the notice on return (MC2); the dashboard
(MC3); several sheets in one fill-in (SH7); the author editor (SH6); a download of a fill-in as an Excel file; "has an attachment" as a
report column; scanning files for malware; a preview of a file; partitions; a history line for the reviewer's edit in the request.
