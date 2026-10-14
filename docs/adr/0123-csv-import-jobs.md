# ADR-0123: CSV import jobs

- **Status:** Accepted
- **Date:** 2026-10-13
- **Sprint:** S17
- **Related:** [ADR-0091](0091-the-write-pipeline-and-its-order.md), [ADR-0111](0111-uniqueness-of-field-values.md), [ADR-0120](0120-files-attached-to-records.md), [ADR-0121](0121-the-object-store-cleanup-and-health.md), [ADR-0122](0122-bulk-record-operations.md)

## Context

Organizations arrive with their data in spreadsheets and in other systems. An import is the first moment the platform writes a hundred thousand records on behalf of a person who is not watching. Four things must hold: it writes exactly what a person could write (no import-only shortcut around permissions or rules); it survives a crash and a deployment; it can be repeated without duplicating (the other system's identifier); and a failed row can be corrected and sent again.

## Decision

1. **An import is always a background job,** started in two requests so that the settings are checked before a large file is sent: `POST .../imports` (operation, mapping, delimiter, dry run, the external identifier field) answers at once with every problem of the mapping (`VALIDATION_ERROR` listing all of them); `PUT .../imports/{id}/file` sends the file (UTF-8 text, its length announced, at most 20 MiB and 100,000 data rows) and queues the job.
   The caller watches `GET .../imports/{id}`. An import belongs to the member who started it: nobody else sees it, not even an administrator, because its report holds the member's data.
2. **It writes as the member who started it.** Each batch runs in a tenant context that names the member, through `Records.create` and `Records.update` (the code of ADR-0122 and of a single write): the gate, the permissions on the object and every mapped field, the pipeline, the unique check, the typed copies, the audit record and the event of each record, the record owner is the member, and whatever Sprint 17A adds applies without a change. A formula, an
   auto-number or a field the member may not write cannot be mapped (the start is refused). A member who left the organization stops the job; a permission taken away refuses the rows from then on, and the report says so.
3. **Resumable and exactly-once per batch.** The job row stores the byte offset after the last committed row. A batch (100 rows) is one transaction that writes its records, its failed rows and the new offset together, so a crash loses nothing and repeats nothing. A job is claimed with a lease renewed by every batch; the sweep (every two minutes, and at the start of the app) takes over a job whose lease expired. One import of an object runs at a time (a partial unique index),
   and an organization has at most three waiting or running. `CsvReader` reads rows as bytes with their end offsets, so resuming does not read the rows before it.
4. **Create and upsert.** `CREATE` makes a record for every row. `UPSERT` finds the record by the value of a field flagged **external identifier** (one indexed read through the query engine) and updates it, or makes it; the same file sent twice changes nothing the second time. An empty cell leaves a field as it is (an import cannot clear a field: a stated limit). A row without its external identifier fails alone.
5. **External identifiers (V047).** A text or number field can be flagged `externalId`. The flag implies **unique and indexed** (ADR-0111, ADR-0101): the database, not the importer, makes sure the other system's identifier occurs once, and the lookup is an index read. A link can be written as the other record's identifier, or as the other record's external identifier when the mapping says `field:externalIdFieldOfTheLinkedObject`; an
   identifier that finds nothing fails the row, one that finds two is impossible by the unique rule.
6. **A row is one savepoint.** A row the pipeline refuses leaves nothing behind and is added to the report; the batch continues. The reason is a message of the pipeline (what is expected, never the value) or of the importer, bounded in length. The report is a CSV (the number of the row, the reason, the row as the file had it, so it can be corrected and sent again), kept as long as the file and the job, at most 10,000 rows of it.
   **Values that a spreadsheet would run as a formula (`=`, `+`, `-`, `@`, tab, return at the start) get an apostrophe in front** in the report (`CsvWriter`).
7. **Dry run.** The same code reads the same file and writes everything in one transaction that is rolled back, so rows that depend on earlier rows see them; nothing remains, and the counts and the report say what a real run would do. It is limited to 10,000 rows for that reason.
8. **A hostile file costs a bounded amount.** A value, a row and the number of values in a row have a largest size; bytes that are not valid UTF-8 make that row a problem; an unclosed quote ends the reading with a clear failure; a zero byte in the first 4 KiB means "not text"; a body that does not announce its length is refused. Cancelling stops the job at the end of its batch (what it wrote stays: there is no undo, and the page says so).
9. **Retention.** The file and the report are removed 7 days after the job ended (`platform.data.imports.retention`), by the same sweep. Nothing of a file — a cell, a column name, a reason — is logged or audited; the audit records are `data.import.started`, `data.import.finished` (counts) and `data.import.cancel_requested`.
10. **Settings** `platform.data.imports.*`: `enabled`, `max-rows` (100000), `max-file-bytes` (20 MiB), `batch-rows` (100), `dry-run-max-rows` (10000), `max-errors` (10000), `max-active-per-organization` (3), `retention` (7d), `lease` (2m), `sweep-interval` (2m).

## Consequences

- An import is as correct as the API, as fast as its batches (the benchmark of the sprint measures rows per second against the single-write path), and cannot be told apart from a very patient person typing.
- It needs the object store for the file (ADR-0121); without one, the file request answers `503` and everything else works.
- There is no screen this sprint; the API steps and a script are in the manual testing document.
