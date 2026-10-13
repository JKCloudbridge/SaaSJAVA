# ADR-0107: Lookup resolution for reading

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S16
- **Related:** [ADR-0092](0092-field-security-on-writes-and-reads.md), [ADR-0097](0097-links-between-records-on-write.md), [ADR-0099](0099-the-reference-directory-links-to-objects-a-platform-module-owns.md), [ADR-0100](0100-the-query-model-and-its-stages.md)

## Context

A list of cases that shows "Account" shows a name, not an identifier. Validation of a link on write exists (ADR-0097, ADR-0099); reading what it points at does not.

## Decision

1. **A query may ask for chosen fields of the record a link points at, one level, by name** (`resolve: [{field, fields}]`). Without it the answer carries the identifier only (the link field's own value).
2. **One batched question per linked object**, however many records the page has (a page of 200 with three link fields asks three questions, not 600).
3. **The field permissions of the member on the linked object apply**: the member's access to the linked object is asked once (`Decisions`), the linked record passes the same read filter as any record, and only fields the member may read are brought. The link field itself must be readable by the member (it is a field of
   the queried object, checked like every field of the query).
4. **One answer for every way a link can fail to show:** the record does not exist, is in the recycle bin, the member may not read the linked object, the linked object is owned by a platform module (a user, a profile ...), or none of the fields asked for is readable: the answer is the link's identifier with no fields. A name
   that is not a field of the linked object, or one the member may not read, is left out without a word; the member learns nothing about an object they may not read and a typo is not told apart from a forbidden field.
5. **Objects a platform module owns (a user) are not resolved in Sprint 16.** The reference directory (ADR-0099) answers yes or no and nothing else by design; reading a name through it is a different contract, built when a screen needs the name of an owner (Sprint 19).
6. **Record-level security (Sprint 17)** will add the linked record's visibility to the same step; until then, as for every read, object and field permission decide.

## Consequences

- The cost of a page with links is bounded by the number of distinct linked objects, not the number of records.
- A reader cannot use a lookup to see a field of a record they could not read directly.
