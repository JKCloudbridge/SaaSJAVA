# The public API

For people who write software that works with the records of an organization. This page is the guide; the exact
contract, with every status of every operation, is the OpenAPI document the platform publishes at
`/api/public/v1/openapi` (no sign-in needed). If this page and the document ever differ, the document is right.

## 1. What it is

A small, stable interface to create, read, change, delete, restore, query and bulk-write the records of the objects of an
organization. Standard objects and the organization's own objects are used in exactly the same way. It is separate from
the interface that the web application uses, which changes whenever a screen does: **this one only grows** (section 12).

It does not offer: setting the organization up (objects, fields, layouts, permissions), files, imports, sharing, saved
views, approvals, workflows or integrations. It gives you the data; the platform's screens keep the administration.

## 2. Where and how to sign in

- **Base address:** the address of your organization, followed by `/api/public/v1`. The organization is always the one
  the address names; nothing you send (a header, a parameter, the body, the token) can choose or change it. A token for
  one organization does not work at another's address.
- **Authentication today:** the access token of a signed-in user, sent as `Authorization: Bearer <token>`. Every call is
  decided by what that user may do, exactly as in the application: the same permissions on the object, the same rights on
  each field, the same rules about which records the user may see or change.
- **Authentication later:** tokens for software itself (client credentials, scopes) arrive in a later release. There are
  no static keys, and there will not be. When scopes exist they can only narrow what the user may do, never widen it.
- **Only for servers.** The interface answers no cross-origin request from a browser page.

## 3. What every answer looks like

A success is `{"data": ...}`; a list of records adds `"pagination": {"limit", "hasMore", "nextCursor"}`. A failure is

    {"error": {"code": "VALIDATION_ERROR", "message": "The request is invalid.",
               "fields": {"salary__c": ["Enter a number."]}, "requestId": "req_...", "traceId": "..."}}

**Branch on `code`, never on `message`.** `fields` (validation only) maps the place that is wrong to its problems; a
place is a field name, or a path such as `records[2].fields` or `filter.items[0].values`. A new code may be added in
the future: treat one you do not know like its HTTP status.

| Code | Status | Meaning |
|---|---|---|
| `VALIDATION_ERROR` | 400 | the request is invalid; `fields` says where |
| `MALFORMED_REQUEST` | 400 | the body is not readable JSON |
| `UNAUTHENTICATED` | 401 | no valid token (missing, expired or revoked) |
| `FORBIDDEN` | 403 | you may see this but not do that |
| `NOT_FOUND` | 404 | it does not exist **or you may not know that it does** |
| `METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `UNSUPPORTED_MEDIA_TYPE` | 405, 406, 415 | the request does not fit the address |
| `CONFLICT` | 409 | the state does not allow it (already deleted; key used for another request) |
| `CONCURRENT_MODIFICATION` | 409 | someone changed the record since you read it |
| `RECORD_LOCKED` | 409 | an approval has the record locked |
| `PAYLOAD_TOO_LARGE` | 413 | the body is over the limit |
| `RATE_LIMITED` | 429 | too many requests in progress; wait `Retry-After` seconds |
| `SERVICE_UNAVAILABLE`, `INTERNAL_ERROR` | 503, 500 | not your fault; quote the request ID |

Every answer carries `X-Request-ID` (send your own, 8 to 64 letters, digits, dot, underscore or hyphen, or one is made) and
`API-Version`. An answer never repeats a value you typed, a key or a token.

**What you cannot learn by asking.** An object that does not exist, one you may not read, one a platform component owns and
one that belongs to another organization all answer the same `NOT_FOUND` in the same words. So does a record you cannot see.
A field you may not read is absent from every answer, and writing it is refused like a field that does not exist.

## 4. Records

    {"data": {"id": "0194f2a8-...", "object": "Employee__c", "recordType": "...", "version": 3,
              "ownerId": "...", "createdAt": "2026-10-31T09:30:00Z", "updatedAt": "2026-10-31T09:45:12.250Z",
              "fields": {"Name": "A. Example", "Salary__c": 1250.50}}}

- The properties above `fields` are the **system block**, always the same. `recordType` is left out when there is none;
  `deletedAt` appears only on a record in the recycle bin.
- `fields` holds the values by field API name. A field with no value is **left out** (never `null`).
- Times are UTC ISO-8601; a day is `2026-10-31`; a link is the identifier of the record it points to.
- **Numbers are the digits stored.** Send a number as a JSON number (`1250.50`), never as text: a number written as text is
  refused. The answer writes every digit it holds, so `99999999999999.99` comes back as exactly that. A program that reads
  JSON numbers into floating-point values rounds beyond 15 significant digits: read decimals with a decimal type.
- The `ETag` of a record is its `version`. Keep it: you need it to change the record.

## 5. Operations

**Find out what exists.**

    GET /objects                      the objects you may read, with what you may do with their records
    GET /objects/{object}             the fields you may read: type, limits, choices, whether you may write it,
                                      the comparisons a filter on it accepts (filterOperators) and whether you can
                                      sort by it. Read these lists; do not copy them.

**Create.**

    POST /objects/{object}/records
    {"fields": {"Name": "A. Example", "Salary__c": 1250.50}}
    -> 201, Location, ETag, the record

**Read.** `GET /objects/{object}/records/{id}` -> 200, `ETag`, the record.

**Change.** A merge: only the fields named change.

    PATCH /objects/{object}/records/{id}
    If-Match: "3"
    {"fields": {"Salary__c": 1300}, "clear": ["Notes__c"]}
    -> 200, the new ETag, the record

`If-Match` is required and is the `ETag` you read. If the record has another version now the answer is `409`
`CONCURRENT_MODIFICATION` and **nothing is changed**: read it again and decide. A `null` value, or a name in `clear`,
removes a value. The write is checked in a fixed order (the record type, which fields you may write, the type of each
value, required fields, links, unique values, validation rules, formulas) and the first step that finds problems answers
all of its problems, each with its place; nothing is saved.

**Delete and restore.** `DELETE /objects/{object}/records/{id}` -> 204 moves the record to the recycle bin (kept 30 days
unless the deployment says otherwise). `POST /objects/{object}/records/{id}/restore` -> 200 brings it back. Deleting what
is already deleted is `409` `CONFLICT`.

## 6. Questions: filter, order, page

    POST /objects/{object}/records/query
    {"filter": {"op": "AND", "items": [
        {"op": "EQ", "field": "Department__c", "values": ["Sales"]},
        {"op": "BETWEEN", "field": "Salary__c", "values": [1000, 2000]}]},
     "sort": [{"field": "Name", "descending": true}],
     "fields": ["Name", "Salary__c"],
     "resolve": [{"field": "Manager__c", "fields": ["Name"]}],
     "limit": 50}
    -> {"data": [records, with "linked" when you asked to follow links],
        "pagination": {"limit": 50, "hasMore": true, "nextCursor": "..."}}

To get the next page send the same body with `"cursor": "<nextCursor>"`. A cursor is good only for the same question. There
is no total. A page may be short and still have a cursor: only the absence of a cursor is the end.

- Comparisons: `EQ NE GT GTE LT LTE BETWEEN IN IS_EMPTY IS_NOT_EMPTY STARTS_WITH CONTAINS`, combined with `AND` and `OR`.
  A text is compared exactly (upper and lower case differ). Which comparisons a field takes is in its description.
- At most 50 conditions, 100 values in an `IN`, two sort keys, 200 per page (50 if you say nothing).
- A filter or an order on a field that is not indexed is allowed on an object with few records (20,000 live records unless
  the deployment says otherwise); above that the answer says which field to flag as indexed. It is never a slow request.
- Records without a value come last in both directions. Sorting by `updatedAt` is not offered.
- You only get the records you may reach, and only the fields you may read.

## 7. Many at once

    POST /objects/{object}/records/bulk
    {"operation": "CREATE" | "UPDATE" | "DELETE", "allOrNone": false,
     "records": [{"fields": {...}}, ...]}            // UPDATE: {"id", "version", "fields", "clear"}; DELETE: {"id"}
    -> 200 {"data": {"committed": true, "succeeded": 2, "failed": 1, "results": [
         {"position": 0, "status": "CREATED", "id": "...", "version": 0},
         {"position": 1, "status": "FAILED", "error": {"code": "VALIDATION_ERROR", "message": "...", "fields": {...}}},
         {"position": 2, "status": "CREATED", "id": "...", "version": 0}]}}

Every record goes through exactly the steps a single request goes through, and the answer has one result for each, in the
order you sent. By default each record stands alone: one that is refused is undone alone. With `"allOrNone": true` one
refusal undoes the whole call (`committed` false; the others say `ROLLED_BACK`). **The call itself is `200` even when
records in it were refused: read `results`.** At most 200 records and 8 MiB per call.

## 8. Repeating a request safely

Send `Idempotency-Key: <a key you choose, 8 to 255 characters>` with a create or a bulk call. If you lose the answer, send
the same request again with the same key: you get the first outcome (`Idempotent-Replayed: true`, the same status) and
**no second record is made**, however many attempts you make and wherever they land. A repeat of a create shows the record
as it is now. A repeat of a bulk call shows the outcome of each position; a record that was refused keeps its error code
with fixed words.

- The key and the request belong to you: the same key with **another** request is `409` `CONFLICT`, and nothing is done.
- A request that was refused, and an all-or-none call that was undone, leave nothing behind: send them again.
- A key is remembered for 24 hours; after that it is free again. What is remembered is identifiers, never your data.
- A change needs no key (its version protects it), a delete answers `409` the second time, a restore answers `409` when the
  record is live.

## 9. Limits

| What | Limit |
|---|---|
| records per bulk call | 200 |
| body of a create, a change or a query | 1 MiB |
| body of a bulk call | 8 MiB |
| page size | 200 (50 by default) |
| conditions in a filter / values in `IN` | 50 / 100 |
| requests in progress for one organization | a platform ceiling; over it the answer is `429` with `Retry-After` |

A body over its limit is refused as soon as the limit is passed, whether or not you announced its length. Quotas and
rate-limit headers arrive in a later release.

## 10. Unknown things are refused

A property that the contract does not have is `400` `VALIDATION_ERROR` with its place (`colour`, `records[2].extra`), never
silently ignored: a misspelled name is told to you at once.

## 11. Worked example: a day in the life of a client

1. `GET /objects/Employee__c` and keep the field names and `filterOperators`.
2. `POST /objects/Employee__c/records` with `Idempotency-Key: hire-0001` and `{"fields": {"Name": "A. Example", "Salary__c": 1250.50}}`. The connection drops. Send the same request again with the same key: `201`, `Idempotent-Replayed: true`, the same `id`.
3. `GET` the record: `ETag: "0"`.
4. `PATCH` it with `If-Match: "0"` and `{"fields": {"Salary__c": 1300}}`: `200`, `ETag: "1"`. Send the same patch again with `If-Match: "0"`: `409 CONCURRENT_MODIFICATION`.
5. `POST .../query` with a filter on `Salary__c`, `"limit": 100`; follow `nextCursor` until there is none.
6. `POST .../bulk` with 200 `UPDATE` items and `Idempotency-Key: nightly-2026-10-31`; read `results` for the ones that failed.
7. `DELETE` the record; `POST .../restore` to bring it back.

## 12. Versioning and deprecation

- **The major version is in the address** (`/api/public/v1`) and in every answer (`API-Version: v1`).
- **Inside version 1 a change only adds:** a new optional property in an answer, a new optional parameter or header, a new
  operation, a new value of something you were told to expect (error codes, field types, comparisons). Nothing is removed,
  renamed, retyped or made required. Write your client to ignore properties it does not know.
- **A change that cannot be made that way is version 2, beside version 1.**
- **Deprecation.** Before an operation (or a property of an answer) is retired it is **announced**: the contract marks it
  `deprecated`, and every answer of it carries
  - `Deprecation: @<seconds>` — the day it was announced,
  - `Sunset: <date>` — the first day it may be gone,
  - `Link: <address>; rel="successor-version"` — what replaces it.

  **At least 12 months pass between the announcement and the sunset day**, and it is not removed before that day. The
  platform's own tests refuse a shorter notice.
- The contract document is published at `/api/public/v1/openapi`, changes only by adding inside a version, and can be cached
  (`ETag`, `If-None-Match`).
