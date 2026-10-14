# ADR-0120: Files attached to records

- **Status:** Accepted
- **Date:** 2026-10-13
- **Sprint:** S17
- **Related:** [ADR-0115](0115-the-record-access-model-and-how-it-reaches-a-statement.md), [ADR-0121](0121-the-object-store-cleanup-and-health.md), [ADR-0012](0012-observability.md), [ADR-0003](0003-rls-defence-in-depth.md)

## Context

A CRM keeps contracts, quotes and photographs next to its records. The platform has had an S3-compatible object storage in its local services since Sprint 0 and has never used it. A file is the first thing the platform stores that is not a row, is large, and is handed to a browser, so the decisions below are the ones where a mistake is a leak, a stored attack or an orphan nobody pays attention to.

## Decision

1. **A file belongs to one record and inherits its permission.** Reading and downloading need the right to read the record; uploading and deleting need the right to change it, both judged by record-level security (ADR-0115, `RecordAuthorization`). A record that cannot be reached, a record in the recycle bin and a file of another record are all answered "not found". Nothing is public.
2. **Bytes stream through the application; no signed address is handed out.** The prompt of the sprint recommended short-lived signed addresses. They are not used, for four reasons that only show when it is built: the address must name a host the browser can reach (in a deployment behind a proxy this is a second public name with its own certificate and its own CORS list); the headers of the answer (`Content-Disposition`, `X-Content-Type-Options`, the type)
   would be set by the store from what was uploaded, not by the platform; a signed address can be copied, mailed and used after the member lost the right (it is good until it expires), while the application re-checks the right on every download; and the audit trail would show the creation of an address, not the download. The price is that the bytes pass through the application, which is bounded: the largest file is 25 MiB, the transfer is a stream
   (a fixed small buffer, nothing in memory), and a download holds no database transaction. The decision is revisited when a measured load needs it: the same `ObjectStore` interface can add a signed address behind a setting without changing a caller.
3. **The key is generated, the name is data.** The bytes live under `files/<organization>/<file>` (two identifiers the platform made); no part of a name a person typed is ever part of a key, a path or a header unescaped. The store has one bucket, no public policy; the credentials are from the secret store (`PLATFORM_STORAGE_*`); nothing in the store is readable without them.
4. **An upload is written row first.** A `pending` row is committed (its own short transaction), the bytes stream to the store with no transaction open, counted and hashed with SHA-256 on the way (`UploadStream`), the row becomes `active` with the real size and checksum. A failure anywhere removes the row, and removing a row queues its key for removal (ADR-0121). The size is the number of bytes that really passed, never the length the client announced; a body longer than announced is cut, a body shorter is refused.
5. **The server decides what a file is** (`FilePolicy`, pure functions): only the last part of the name is kept, control characters, path separators and anything that could break a header or a log are replaced, the length is bounded; the declared type is accepted only when it has the shape of a type and is neither on the denied list (programs, scripts, pages a browser would run, `image/svg+xml`) nor outside an allowed list when a deployment names one, else the file is
   stored and served as `application/octet-stream`; a name ending in a denied extension is refused whatever the declared type; the first bytes are looked at too (a program header, an interpreter line, a page), so a renamed file is refused. This is a screen, not a scanner: **scanning for malware is a deployment choice** (a scanner in front of the store, or on the event of an active file) and is named in the manual steps of Sprint 17 (the runbooks come with the hardening sprints).
6. **Every download is an attachment.** `Content-Disposition: attachment` with the cleaned name, `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`, and the type the server decided. There is no inline display, so a stored page can never run in the platform's origin.
7. **Limits:** 25 MiB a file, 20 files a record (pending included), a name of 255 characters; `platform.data.files.*`. A body is bounded by the length announced before it is read (`413`); the proxy bounds a body that announces none (the deployment requirement of Sprint 14 extends to this endpoint).
8. **What is written down:** audit `data.file.uploaded`, `data.file.downloaded` and `data.file.deleted` with the object, the record, the file identifier and (for an upload) the size; the event of the record is not changed. Names, types and bytes are never in a log, an audit record or an event (`RecordAccessFlowsLogsAreCleanIT`).
9. **The files of a record go when the record goes for good,** and not before: a record in the bin keeps its files and they return with it; the purge of the bin and an explicit removal for good delete the file rows (a cascade) and the trigger queues every key (ADR-0121). A file cannot be moved to another record.
10. **The store is mandatory where the platform is deployed, optional where it is developed.** The `prod` profile has no default for the bucket and the credentials, so a deployment without a store does not start (a silent `503` on every file would be found by a customer, not by a deployment check). The `local` profile makes its bucket at start; a test or a developer run with no store configured is supported: files and imports answer `503 SERVICE_UNAVAILABLE` with a retry hint and everything else works.

## Consequences

- The platform owns the whole path of a file, so the right is re-checked on every download and the audit trail is complete.
- Large files cost application bandwidth; the 25 MiB cap and the streaming keep that bounded and measured (the benchmark times an upload and a download of a 25 MiB file).
- Previews, thumbnails, versioning of a file and virus scanning are not built; each has a place to attach (the active state, the event, the key).
