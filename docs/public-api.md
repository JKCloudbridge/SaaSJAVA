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
- **Authentication:** an access token, sent as `Authorization: Bearer <token>`. There are two kinds. For **software**
  (your server, a scheduled job) it is the token of a **connected application** that an administrator registers
  (section 2a, the way to use). The other is the access token of a signed-in person. In both cases every call is
  decided by what the member behind the token may do, exactly as in the application: the same permissions on the object,
  the same rights on each field, the same rules about which records they may see or change.
- **There are no static keys, and there will not be.** A secret is only ever exchanged for a short-lived token; the
  interface accepts no secret as a credential.
- **Only for servers.** The interface answers no cross-origin request from a browser page.

### 2a. Connected applications: how software gets a token

An administrator of the organization registers a **connected application** (Setup, Connected applications). The
registration gives you a **client identifier** (starts with `ca_`, not a secret) and, once, a **client secret** (starts
with `cs_`). Copy the secret at once into the place your software keeps secrets: it is shown only when it is made and
cannot be shown again; if it is lost, make a new one.

**Whom the application acts as.** It acts as the member who registered it, for ever, with exactly that member's
permissions and nothing more. The scopes and the list of objects the administrator chose can only narrow that. Nobody can
change whom an application acts as. **Advice:** register an integration as a *dedicated integration member*, an
ordinary, licensed member that an administrator creates for the purpose (for example `integration-a@example.test`, with an
access policy that gives only what the integration needs), not as a named employee. If a person leaves, the applications
they registered stop at once; a dedicated member means a change of staff never stops your integration. Nothing is
transferred automatically: if an application shows as stopped, register a new one as another member and remove the old.

**Asking for a token.** Send the identifier and the secret to the token address of your organization,
`/api/v1/oauth2/token` (on the same host as the interface). Either with Basic authentication (preferred):

    POST /api/v1/oauth2/token
    Authorization: Basic <client identifier, a colon, client secret: all in Base64>
    Content-Type: application/x-www-form-urlencoded

    grant_type=client_credentials&scope=records.read records.write

or with the two values in the body:

    POST /api/v1/oauth2/token
    Content-Type: application/x-www-form-urlencoded

    grant_type=client_credentials&client_id=<identifier>&client_secret=<secret>&scope=records.read

The answer is `200` with `access_token`, `token_type` (`Bearer`), `expires_in` (seconds) and `scope` (the scopes granted).
Send the token as `Authorization: Bearer <access_token>` to the interface. **There is no refresh token**: when the token
is about to end, ask again. A token lives 15 minutes unless the administrator chose 5 to 60; read `expires_in`, do not
assume. A token is for the organization of the host it was issued on only.

**Scopes.** Leave `scope` out to get every scope the application holds, or name the ones you need (separated by spaces).

| Scope | Allows |
|---|---|
| `objects.read` | `GET /objects` and `GET /objects/{object}`: which objects exist for the member and their fields. Reads no record |
| `records.read` | read one record, and query records |
| `records.write` | create, change, delete, restore, and bulk calls |

A scope never includes another: `records.write` does **not** include `records.read`. A scope is necessary, never
sufficient: it lets the call reach the operation, and what the member may do with the object, the field and the record
is decided as always. An operation whose scope the token lacks is `403` `FORBIDDEN`. An application may also be limited
to some objects: any other object answers `404` `NOT_FOUND` exactly like an object that does not exist, and is not listed.

**Errors of the token address** (a different, standard form: `{"error": "...", "error_description": "..."}`):

| Answer | Meaning |
|---|---|
| `401` `invalid_client` | the identifier or the secret is wrong, or the application is off, or its member can no longer act, or it is not available to the organization. **One answer for every cause on purpose**: it never says which |
| `400` `invalid_scope` | you asked for a scope the application does not hold |
| `400` `unsupported_grant_type` | only `client_credentials` is offered |
| `429` `temporarily_unavailable` | too many failed attempts from your address or for this identifier; wait `Retry-After` seconds. Failed attempts only are counted |

A token that has ended, been revoked or whose application was switched off, narrowed or removed is `401` `UNAUTHENTICATED`
at the interface. An application's token reaches the interface and nothing else.

**Rotating a secret.** An administrator makes a new secret; the old one stops working **at once** unless they chose an
overlap of up to 24 hours so that you can change over without a gap. Tokens already issued live until they end, unless
the administrator chose to end them now. Plan the change: store the new secret, then ask for tokens with it. Rotate when a
person who knew the secret leaves, and at any sign of a leak (see the runbook). Many calls in a row after a refusal are
counted as failed attempts; do not retry a `401` in a loop.

**From a person's token to a client.** If your software borrowed a signed-in person's token until now: (1) create a
dedicated integration member and give it the access policy the software needs; (2) sign in as that member and register a
connected application (or ask an administrator who holds the ability to manage connected applications to do it as that
member: it acts as whoever registers it); (3) give the software the identifier and the secret, ask for tokens as above and
send them as before: nothing else about the calls changes; (4) stop using the person's token. Listing the objects then
shows what the member may read, limited by the application's list if it has one.

A change made through a connected application is recorded in the organization's audit trail with the member **and the
application** (`via_client`), so an administrator can tell your software's changes from a person's.

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
| `UNAUTHENTICATED` | 401 | no valid token (missing, expired, revoked, or the application behind it was switched off or removed) |
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

A body over its limit is refused as soon as the limit is passed, whether or not you announced its length. How many requests
you may make is section 9a.

## 9a. Rate limits and quotas (the rate, the day, and the headers that tell you)

Every request to an operation of this interface counts, so that one customer, or one connected application, cannot use up what
the others share. The description document, the token address and the application's own screens are not part of it.

| What | Default |
|---|---|
| requests a minute for **each connected application**, and for **each person's own token** | 600 |
| requests a minute for the **whole organization**, whoever asks | 3,000 |
| requests a day for the whole organization (the day ends at midnight UTC) | 500,000 |

The platform can set other numbers for one organization. Read the organization's own numbers, and how much is used, in Setup, "API
usage" (or `GET /api/v1/usage` with the right to see it); they are numbers only.

**What counts.** A request costs 1. A **bulk call costs as many requests as it has records** (at most 200); one that does not
fit in what is left is refused whole. A repeated request with an `Idempotency-Key` counts too, because it reached the server.
A request refused for a missing token or scope is not counted. A request is counted against **your application**, and also against
the **organization**, so a thousand applications cannot exceed the organization's number.

**The headers.** Every answer of a counted operation says where you stand, in the form of the IETF draft on rate-limit headers:

```
RateLimit-Policy: "minute";q=600;w=60, "day";q=500000;w=86400
RateLimit: "minute";r=120;t=33, "day";r=499000;t=40000
```

`q` is what the window allows, `w` its length in seconds, `r` what is left, `t` the seconds until it ends and the count starts
again. The item for the minute is the tighter of your application's own number and the organization's; the answer never says
which it is.

**When a limit is reached** the answer is `429` `RATE_LIMITED` with `Retry-After` in whole seconds, the same two headers, and one
fixed sentence that never says which limit was full. Nothing was done. Wait `Retry-After` seconds and ask again.

**What a well-behaved client does:** read `RateLimit` on every answer and slow down before `r` reaches 0; wait `Retry-After`
on a `429` and never retry at once; spread bulk calls out instead of sending them in a burst; and remember that a webhook (section
13) is told to you so that you need not ask. A request that is refused because the organization has too many requests in
progress at once is also a `429` with `Retry-After`, without the headers: wait the same way.

## 10. Unknown things are refused

A property that the contract does not have is `400` `VALIDATION_ERROR` with its place (`colour`, `records[2].extra`), never
silently ignored: a misspelled name is told to you at once.

## 11. Worked example: a day in the life of a client

0. Ask the token address for a token with your identifier and secret (section 2a); renew it before `expires_in` runs out.
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

## 13. Webhooks: being told instead of asking

A webhook is a standing instruction of your organization: "when records change, tell this https address." An administrator makes
it in Setup, under Webhooks, chooses the events and, if wanted, the objects, and the platform calls your address with a small
signed message. It spares you from asking over and over whether anything changed.

**What is sent.** One `POST` with a JSON body of identifiers only: no field name and no value.

```
{"payloadVersion":1,"id":"<delivery>","type":"record.updated","createdAt":"<time>","object":"Employee__c",
 "recordId":"<record>","version":5}
```

`type` is one of `record.created`, `record.updated`, `record.deleted`, `record.restored` (the screen lists them); a test sends
`ping`, with no object and no record. A property is only ever added to the message. **Fetch the record** through this
interface with your own credentials to read what it holds: what you learn is decided by the member behind those credentials.

**Whom it tells about what.** A webhook **acts as the member who made it**, for ever. It tells you about a record only if that
member can read it at the moment of the delivery; one that member cannot read is not sent (the administrator sees a counted skip,
never the record). When that member can no longer act the webhook stops, and a new one is made by a member who can. The
practice is a dedicated integration member. A removed record is announced only to a member who could read every record of the
object.

**The headers of a delivery.**

| Header | Meaning |
|---|---|
| `Webhook-Id` | The identifier of the delivery. **Keep the ones you have handled and ignore a repeat.** |
| `Webhook-Event` | The event, the same as `type` in the body |
| `Webhook-Timestamp` | Whole seconds since 1970 when the delivery was signed |
| `Webhook-Signature` | One `v1=<hex>` for every signing secret, separated by a space: two while a replaced secret overlaps |
| `Webhook-Attempt` | The number of this attempt |

**Verify the signature on every delivery**, before you trust anything in it. In steps, with no library of ours (the secret is the
text you were shown once, `whsec_...`):

```
body       = the request body, byte for byte, exactly as it arrived (do not parse and write it again)
timestamp  = the value of Webhook-Timestamp
if abs(now_in_seconds - timestamp) > 300:  refuse                      # a recorded delivery sent again later
signed     = timestamp + "." + body                                    # the number as text, a full stop, the body
expected   = lower_case_hex( HMAC_SHA256( key = secret_as_utf8, message = signed ) )
for each part of Webhook-Signature split on spaces:
    if part starts with "v1=" and constant_time_equals( part after "v1=", expected ):  accept
refuse
```

Compare with a function that takes the same time whatever the content (not `==`). Accept either signature while a secret is
being replaced: keep both secrets until the overlap ends.

**Answer quickly with a `2xx`.** Anything else is a failure. Only the status is read; the body of your answer is never read,
kept or logged, so do not rely on it to say anything to us.

**Duplicates and retries.** A delivery is sent **at least once**: an answer that is lost, or an event the platform hands on
twice, can bring the same delivery again, with the same `Webhook-Id`. A failure that may pass (a timeout, a connection that
failed, `408`, `429`, `500`, `502`, `503`, `504`) is tried again after a wait that doubles, with `Retry-After` honoured;
**`410 Gone` and every other `4xx` are not tried again**; after five attempts a delivery is set aside, and an administrator may
send it again (**replay**, which carries the same `Webhook-Id`). Several updates of one record that are still waiting become
one. There is **no promise of order** between deliveries: treat each as "this record changed" and fetch it.

**Failing for a long time.** An address that fails for seven days without one success is switched off by the platform, the
administrators are told, and nothing is deleted; switching it on again needs a new test of the address.

**The address.** It must be an https address of a public host: no address of a private network, no user name or password in it,
no query, and it is never followed to somewhere else (a redirect is a failure). Making or changing a webhook, the administrator
sends a **test**, a signed `ping`; a webhook is switched on only for an address that answered a test as it stands.

**The signing secret** is shown once, when it is made, and the platform keeps it sealed so that it can sign, but cannot show it
again. To replace it (or if it leaked) make a new one: you choose how long, up to 24 hours, the old one keeps signing as well so
that you can change over without losing a delivery; **0 ends the old one at once.**

**Limits.** An organization may have 50 webhooks; the platform calls one address at most 20 times a second and has 10 calls to
it under way at once. The limits of section 9a are on what you ask of the platform, not on what it tells you.
