# ADR-0085: The record identifier

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S14
- **Related:** [ADR-0010](0010-base-schema-conventions.md), [ADR-0084](0084-the-record-store.md)

## Context

A record needs an identifier that is opaque (it never reveals how many records exist), time-ordered (index locality, and a stable order for paging), unique across organizations (an identifier can never name two records), and safe (it is
never proof of anything: the organization always comes from the host, ADR-0003).

## Decision

- **A version 7 UUID, made by the database** (`default uuidv7()`, PostgreSQL 18), returned to the application by the insert (`returning`). The application does not make identifiers for records.
- **Why the database and not the application.** One source for every writer: the endpoints today, the bulk import and the upsert of Sprint 17, and any manual correction. The benchmark's bulk scenarios used the default and passed with it. Java 25
  has no built-in version 7 generator (the shared kernel has `Uuids.v7()` for the rare case an identifier is needed before the insert); a record needs it only after. Identifiers made by PostgreSQL are monotonic within a session, and across
  sessions they are ordered by their millisecond and then by random bits, which is enough for paging (a page is "after this identifier", and a record that is created while paging may or may not appear: that is the nature of keyset paging).
- **Opaque, not secret.** An identifier shows its creation time to the millisecond, nothing else. It is not a count. Nothing is derived from it.
- **Never trusted.** A path with an identifier names a record only inside the organization of the host: every statement filters by organization and object, and row level security enforces it again. An identifier of another organization, of
  another object of the same organization, or one that never existed all answer "not found" alike.
- **Paging.** A page is "the records after this identifier"; the cursor is the identifier, wrapped by the API's cursor type. A cursor that is not an identifier is a validation error.

## Consequences

- The identifier is globally unique, so the primary key is just `(id)` (ADR-0010) and an import can say "this identifier" for an upsert without naming the organization.
- If the application ever needs the identifier before the insert (an event that must carry it before the statement), `Uuids.v7()` exists and the column accepts it.
