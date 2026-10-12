# ADR-0087: Optimistic concurrency and how a record changes

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S14
- **Related:** [ADR-0010](0010-base-schema-conventions.md), [ADR-0011](0011-api-conventions.md), [ADR-0084](0084-the-record-store.md), [ADR-0086](0086-soft-delete-the-recycle-bin-and-its-retention.md)

## Context

Two people open the same record, both change it, both save. The second must not silently overwrite the first. Records are the data people care about most, so the rule must hold across requests, instances and the database, not just in a service.

## Decision

- **A change carries the version it was based on** (`version` in the body of the change). The statement is
  `update ... set version = version + 1 ... where tenant, object, id, version = :expected and deleted_at is null`; one statement, one row. If it matches nothing, nothing changed. The row guard of the database (ADR-0010) refuses any update whose
  version is not exactly the old one plus one, so a path that forgot the bump fails in a test instead of losing data.
- **The answer to a lost race** is `409` with the code `CONCURRENT_MODIFICATION`, with the stable message of the error model and nothing else: no value, no name, no "who changed it". When the statement matched nothing the service reads the row once to say
  *why*: no such record (404), in the recycle bin (409 `CONFLICT`, ADR-0086), or another version (409 `CONCURRENT_MODIFICATION`).
- **A change merges.** The fields named take their value; a field whose value is null, or that is listed in `clear`, loses its value (its key is removed, so "no value" has exactly one form and the object usage check can ask "does a record have this key");
  every other field keeps its value. The merge is done by the database in the same statement (`payload || patch`, nulls stripped), so two changes of different fields that start from the same version cannot both succeed, and a change never
  overwrites a field it did not name with an older copy. An empty change is refused: it would raise the version for nothing.
- **Delete, restore and remove for good do not carry a version.** Deleting a record that someone changed a moment ago is what the person asked for; the version still rises, and the audit record says which version it was.
- **Where it is proved:** one request and eight at the same moment (exactly one wins), two real application instances against one database, writers that retry after a conflict (no increment is lost), and a delete racing a change.

## Consequences

- The client keeps the version it read and sends it back; the generated API client types it. A stale version is a conflict, never an error of the platform.
- `PUT` (replace the whole record) is deliberately not offered: replacing would turn a field the sender did not know about into a deletion. A form that wants to send everything can send everything with `PATCH`.
- The version is per record, not per field: two people who change different fields of the same record still conflict; the second reloads and sends again. Field-level merge would need per-field versions; not worth the size of the payload.
