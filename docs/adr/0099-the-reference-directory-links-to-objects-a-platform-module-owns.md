# ADR-0099: The reference directory: links to objects a platform module owns

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S15A
- **Related:** [ADR-0063](0063-relationships.md), [ADR-0084](0084-the-record-store.md), [ADR-0091](0091-the-write-pipeline-and-its-order.md), [ADR-0097](0097-links-between-records-on-write.md)

## Context

Sprint 15 refused a value in a lookup whose target is an object a platform module owns (a user, a profile, a role ...), because the rows of such an object live in that module's tables and the record store may not read them (ADR-0097 point
5). The gap is visible: the most common lookup of a CRM is an owner or a manager, which is a user. The module graph also has to stay as it is: `data` must not depend on `identity`.

## Decision

1. **A contract in the shared kernel:** `ReferenceDirectory` with two methods, `object()` (the API name of the platform-owned object it answers for, for example `User`) and `exists(UUID)` (is this identifier a record of that object that a record of
   the current organization may link to). The owning module implements it as a bean; `WritePipeline` collects every implementation from Spring, so **no module edge is added** and `data` knows no module that owns an object. The shared kernel
   already carries such contracts for the same reason (`SupportAccess`, the audit and event contracts).
2. **The answer is a yes or no and nothing else:** no name, no label, no count, no way to list. A directory that could be asked to describe or enumerate would leak what the record store must never show. Missing, belonging to another
   organization and no longer active all answer the same, and the pipeline turns that into the one answer for a missing link ("The linked record does not exist.", ADR-0097).
3. **Run in the caller's tenant context and transaction**, after the context was opened (like every metadata and data read). The implementation filters by the organization in addition to row level security.
4. **First implementation: `User`, in the identity module (`UserDirectory`).** The identifier is the person's account identifier; it exists for this organization when the person has an **active** membership of it. A deactivated member and a person
   who is not a member answer "no". A link that already exists to someone who is deactivated later is not touched: the record store only checks a link when it changes (ADR-0097 point 2), so a leaver never blocks a record from being saved.
5. **Other platform-owned objects stay refused** ("Links to this kind of object are not available yet.") until a module implements the contract for them. `Profile` and `Role` (module `security`) are about twenty lines each and are added when a
   real field needs them; adding one is: implement the interface, add a test next to `UserLinksIT`.

## Consequences

- One query per changed link to a platform-owned object (the same shape as the existing one question per target object for ordinary links).
- The contract is deliberately narrow so that it cannot become a back door into another module's data. A request for more than yes or no (a display name next to an owner field, for example) is a different feature: the query engine of Sprint 16
  resolves lookups for reading, through a read contract of its own.
