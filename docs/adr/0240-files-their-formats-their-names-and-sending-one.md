# ADR-0240: Files: their formats, their names, and sending one (S29A)

- **Status:** Accepted
- **Date:** 2026-10-31
- **Sprint:** S29A
- **Related:** [ADR-0216](0216-the-canonical-model-between-a-record-and-an-external-entity.md), [ADR-0220](0220-the-outbound-flow-and-its-reliability-line.md), [ADR-0228](0228-repeat-safe-sends-and-the-extra-headers-of-an-adapter.md), [ADR-0237](0237-the-file-transport-seam.md)

## Decision

1. **Two formats, a closed list** (`FileFormat`), the same in both directions for one connection: **comma separated values**
   with a header line, and **one flat JSON object per line**. There is no template text and no way to describe a layout: a
   file is a table. Both are read and written by strict code of the platform (`CsvFiles`, `JsonLinesFiles`), no library.
2. **Reading is all or nothing and says only why.** UTF-8 text only (a leading byte order mark is accepted), no control
   character but tab and line breaks, double quotes for a value that holds a comma, a quote or a line break, a first line
   that names the columns (a plain name, once, and the identifier column among them), every row as long as the first, and
   bounds: 200 columns, 10,000 characters a value, 200 characters an identifier, `maxRows` rows (default 10,000). A problem
   is one of five stable keys (`file_empty`, `file_not_text`, `file_too_many_rows`, `file_header_invalid`,
   `file_row_invalid`) and never repeats a word of the file. An empty value is a value that is not there. In a line file a
   list or an object is refused; null is a value that is not there.
3. **A row is written with its identifier and what happened.** The first two columns (or keys) are the identifier column
   (`id` by default) and the operation column (`operation`: `create`, `update` or `delete`); then the mapped values in the
   order they came. The identifier of an entity in a file is **the record's own identifier**: a file has no service that
   names things, so the person who reads the file and the platform agree on what a row is about. A field that cannot be a
   column (it clashes with those two, or its name is not plain) or a value with a control character makes the send
   unwritable (`payload_unwritable`, permanent). A value is written as it is: a value that begins with `=` is not altered,
   because the file is for a system and not for a person's spreadsheet (the Excel writer of the platform, which is for
   people, writes every cell as text).
4. **One file per delivery, named from the delivery.** `d-<delivery id>-g<generation>.<ending>`; never from a value of a
   record. A repeat of the same send (a lost answer) is the same name; a replay is a new generation and so a new name.
5. **A reader never sees half a file.** The content is written under a temporary name that starts with a dot
   (`.<name>.<random>.part`) in the same directory, its size is checked, and it is renamed into place (a rename onto a name
   that exists is refused by the platform before it is tried). Every reader of the directory is told to ignore names that
   start with a dot; the platform's own pick-up never takes one.
6. **What is promised, and what is not.** Before writing, the platform looks at the final name: if a file is there with the
   size of this content the earlier attempt completed and only its answer was lost, so nothing is written again and the
   delivery is done. If a file is there with another size, the attempt fails as `file_failed` (retried, then set aside).
   Promised: one file for each delivery generation, as long as the receiver keeps the file until a repeat could happen.
   **Not promised:** that a receiver which *removes* a file between the write and a repeat caused by a lost answer sees it
   only once; a receiver that cannot tolerate a repeat deduplicates on the name, not on the content. A connection cut in the
   middle of a write leaves only a `.part` file and no final name, and is retried from the start.
7. **Settings of the type** (`file-transfer`): `hostKey`, `format`, the five directories (`outboxDirectory`,
   `inboxDirectory`, `processingDirectory`, `doneDirectory`, `errorDirectory`, each a `DIRECTORY`: slash separated names of
   letters, digits, dots, dashes and underscores, at most 20 deep, no dot-only name, never relative; pairwise different),
   `inboundEntity` (the kind of entity the inbox holds; empty means nothing is picked up), `idColumn`, `operationColumn`,
   `pollMinutes` (1 to 1440) and `timeoutSeconds` (5 to 120). The platform never deletes anything on the server.

## Consequences

A file that needs a layout of its own (a fixed width, a different delimiter, a nested document) is a need the type cannot
express; it is never half-supported.
