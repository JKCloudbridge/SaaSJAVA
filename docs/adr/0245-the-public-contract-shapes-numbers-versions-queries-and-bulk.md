# ADR-0245: The public contract: shapes, numbers, versions, queries and bulk (S30)

- **Status:** Accepted
- **Date:** 2026-10-31
- **Sprint:** S30
- **Related:** [ADR-0089](0089-what-a-record-may-write-and-what-it-may-never-leak.md), [ADR-0100](0100-the-query-model-and-its-stages.md), [ADR-0122](0122-bulk-record-operations.md), [ADR-0244](0244-the-public-api-its-address-its-closed-table-and-the-rules-that-carry-over.md)

## Decision

1. **The record.** `{id, object, recordType?, version, ownerId, createdAt, updatedAt, deletedAt?, fields}` in the
   envelope `{"data": ...}`. The properties above `fields` are the **fixed system block**; `fields` holds the values keyed by
   field API name. A field without a value is left out, and so is a field the caller may not read: **absent, never null,
   never masked**. `createdById` and `updatedById` exist as system fields of the object but are not part of V1's record
   (the internal record does not carry them either); they can be added without breaking anyone. A query adds `linked`
   (by link field: the identifier and the fields asked for of the record it points at).
2. **Types.** Every type is the public type of its own class, named `Public...`, so no schema name is shared with the
   internal document (a test requires it). Times are UTC ISO-8601 (`2026-10-31T09:30:00Z`), days are `2026-10-31`, a link is
   the identifier of the record.
3. **Numbers are the digits typed.** A number in a request is a JSON number, never text (a number written as text is
   refused at its field with `VALIDATION_ERROR`). The request is read with decimals as decimals and the record keeps what
   it was given; the answer writes the digits it holds. A client that reads JSON numbers into IEEE doubles rounds
   beyond 15 significant digits: such a client must read a decimal with its own decimal type. The contract says so with
   examples (`docs/public-api.md`). Filter values may be a number or its text.
4. **Unknown keys are refused, with the place, never ignored.** The application's mapper is lenient (the internal
   interface has always been, and its clients rely on it), and an annotation on a type cannot make a lenient mapper strict,
   so the public types are read by a reader of their own: the application's mapper with `FAIL_ON_UNKNOWN_PROPERTIES` on
   (`StrictRequestBodies`). It reads only the types of `publicapi`; every answer is still written by the application's
   mapper. A property that is not in the contract answers `VALIDATION_ERROR` at its place (`colour`, `records[2].extra`),
   a value of the wrong type at its place with fixed words. Only a name shaped like an API name is echoed; anything else
   is answered as `request`, so a body can never put its own text into an answer.
5. **Changing a record: the version is the ETag.** `GET` and every write answer `ETag: "<version>"`; `PATCH` requires
   `If-Match: "<version>"` (quoted digits; `*`, a missing header or any other text is `VALIDATION_ERROR` at `If-Match`,
   without repeating what was sent). A lost race is `409 CONCURRENT_MODIFICATION` and changes nothing. We chose the header
   over a field in the body because it is how HTTP names a precondition, and it keeps the body a pure merge. A bulk
   change carries `version` in its item, because a call has no single record to be conditional on. A new error code was
   not needed (a missing header is a validation error, not a new "precondition required" status).
6. **Query.** The body is the closed `RecordQuery` model: a filter tree, up to two sort keys, a projection, links to
   follow (one level), a page size of 1 to **200** (default 50) and an opaque cursor. It passes the same analyzer stages
   (shape, fields with the member's field rights, operators and values, record security, plan). Limits are the engine's:
   at most 50 nodes, 100 `IN` values and the depth setting; unindexed filters are allowed up to the scan limit (20,000
   unless a deployment says otherwise) and refused above with the field to flag, never a slow request. `updatedAt` is not
   offered for ordering (it is not indexed). In front of the engine the mapper refuses a filter over 200 nodes or 16
   levels, so reading one cannot exhaust the stack. A `GET` with `field=value` filters is **not** added: it would need a
   second parser; the single `POST .../query` does the job and no value goes into an address.
7. **Bulk.** The semantics of `BulkRecords`, unchanged (it is the same service, exposed as the contract `RecordBulk`): at
   most 200 records, a savepoint per record, `allOrNone`, a result per record in input order. The public answer says per
   item `CREATED`, `UPDATED`, `DELETED`, `FAILED` (with the same stable `error` a single request would give) or
   `ROLLED_BACK`. Body cap 8 MiB, by setting.
8. **Describe.** `describeObject` reuses the code that tells the screens what to draw (`ObjectForms`), through the
   `ObjectDescriptions` contract, so the fields, which of them are writable, the types, limits and choices, and the
   comparisons each field accepts (`filterOperators`, `sortable`) are read from the query engine and never copied into
   documents. It lists only fields the caller may read.
9. **Sizes.** A body over `platform.publicapi.limits.max-body-bytes` (1 MiB) or, for bulk, `max-bulk-body-bytes` (8 MiB)
   is `PAYLOAD_TOO_LARGE`, refused on the announced length before it is read and, for a body sent without a length, as soon
   as the count passes the limit (the server never holds more than the limit). This is stricter than the internal
   interface, which relies on the front proxy for a body without a length.

## Consequences

The shapes are small, the digits survive, and a client learns the rules from one document. The strict reader is the
one place where the two interfaces differ in how they read, on purpose.
