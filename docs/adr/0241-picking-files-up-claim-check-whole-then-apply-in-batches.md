# ADR-0241: Picking files up: claim, check the whole file, apply in batches (S29A)

- **Status:** Accepted
- **Date:** 2026-10-31
- **Sprint:** S29A
- **Related:** [ADR-0221](0221-the-inbound-flow-the-receiver-and-the-signature.md), [ADR-0222](0222-the-inbound-write-scope-and-the-echo.md), [ADR-0240](0240-files-their-formats-their-names-and-sending-one.md), [ADR-0242](0242-where-the-poll-runs-the-ledger-and-what-a-restart-finds.md)

## Decision

1. **A file is taken by renaming it out of the inbox.** The rename into the processing directory is atomic on the server,
   so two instances can never take one file, and the name there is `<ledger id>__<original name>`: the claim first, so a
   restart finds it again. A name is picked up only if it is plain (`[A-Za-z0-9][A-Za-z0-9._-]{0,99}`, no `..`, no trailing
   dot), has the ending of the format (any case) and is an ordinary file; a link, a directory and a name that starts with a
   dot are left where they are. Names and paths come from one pure class (`FilePaths`) and a corpus
   (`hostile-paths.txt`) holds every spelling that must never be accepted.
2. **The whole file is read and checked before the first row is written.** The file is read with a cap (`maxFileBytes`,
   default 25 MiB; a larger one is set aside without being read to the end), parsed by the adapter (ADR-0240), and **every
   row is mapped** (`InboundService.mappingProblem`, no write) before any is applied. A file with a problem is **set aside
   as a whole** with a stable reason (`file_empty`, `file_not_text`, `file_too_many_rows`, `file_header_invalid`,
   `file_row_invalid`, `file_too_large`, or the reason the mapping gives such as `required_missing`): the ledger says
   `REJECTED`, the file moves to the error directory, **nothing of it is applied**.
3. **The rows are applied by the receiver's own code.** `InboundService.applyEntity` is the middle of the code that handles a
   signed change, extracted so the file poller and the receiver share it: the same mapping, the same scope of the
   connection, the same link (so a repeated row is an update, not a second record), the same write pipeline as the
   platform, the same echo suppression. There is no private way to write records (ADR-0122, ADR-0222); the architecture test
   that only the receiver depends on `BackgroundWrites` still holds.
4. **In bounded batches, with the progress in the same transaction.** `batchRows` (default 200) rows are written in one
   transaction, each row in a savepoint of its own, and the ledger's `rows_done` is advanced in that very transaction, only
   if it is still what the poller believed (`FileBatchPlan` is the pure arithmetic: the next batch always starts at the rows
   done). So a restart resumes at exactly the first row that was not written: no row is repeated, none is skipped.
   Each batch asks again whether the connection is still usable; if it was switched off, the file is left as it is.
5. **Row level versus file level atomicity (the decision).** *File level for validation*, *row level only for what the
   record store refuses at write time*. A row that the rules of the record store refuse (a locked record, a rule of the
   organization) is counted as refused and its savepoint is undone; the other rows of the file are applied. A file with any
   refused row is then moved to the **error** directory (not done) with the reason `file_rows_refused` and the count, so a
   person always sees it; the accepted rows stay, and putting a corrected file back is safe because a repeated row is an
   update by its external identifier (unchanged rows are skipped by their hash). Counts are shown, rows are never shown.
6. **The same file twice is applied once.** A file is identified by a hash of its name, size and modification time. A file
   that comes back with the key of one applied completely is moved from the inbox to the done directory (under a fresh
   claim prefix) and nothing is applied; the unique index `integration_file_done_once` holds this at the database. A file
   that was *set aside* is deliberately not recognised, so a person who moves it back to the inbox gets it tried again.
7. **The end of a file is a move on the server and a line in the ledger, in that order.** After the last row the ledger says
   `APPLIED`; the file is renamed to the done (or error) directory; then the ledger says `DONE` (or `SET_ASIDE`). A restart
   between any two of these finds the ledger row and finishes the job; if the file was already moved, the move is skipped.
   The platform deletes nothing on the server: the done and error directories grow until a person tidies them.

## Consequences

The platform may take a file, find it bad and put it in the error directory: that is the design, and the runbook tells a
person what to do with one.
