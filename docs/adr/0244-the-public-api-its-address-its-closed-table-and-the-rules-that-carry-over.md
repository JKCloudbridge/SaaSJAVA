# ADR-0244: The public API: its address, its closed table of operations, and the rules that carry over (S30)

- **Status:** Accepted
- **Date:** 2026-10-31
- **Sprint:** S30
- **Related:** [ADR-0006](0006-no-static-api-keys.md), [ADR-0011](0011-api-conventions.md), [ADR-0017](0017-tenant-resolution-by-hostname.md), [ADR-0127](0127-custom-page-scripts-and-the-sandboxed-iframe.md), [ADR-0245](0245-the-public-contract-shapes-numbers-versions-queries-and-bulk.md)

## Context

The 285 paths under `/api/v1` are the **internal** interface: they change whenever a screen does, and the document that
describes them is not served in `prod` (ADR-0011 item 8 left publishing to this sprint). A customer's own software cannot
build on that. It needs a small, stable, documented interface over the dynamic objects with its own contract and its own
promise of how it changes.

## Decision

1. **A separate prefix, `/api/public/v1`**, beside `/api/v1`. The internal interface keeps changing; the public one only
   adds (ADR-0247). The public controllers are **new classes** in a new module `publicapi`; no internal controller is
   mapped under the new prefix (an architecture test scans for it), and none is re-exported.
2. **A new door on the same house.** Every operation calls `Records`, `RecordBulk`, `IdempotentWrites` or
   `ObjectDescriptions` of the `data` module. The gate, field security, record security, record locks, the write
   pipeline, the audit trail and the events therefore apply unchanged. `publicapi` adds no rule, no write path and no
   second implementation of who may see a record, reads no table and writes no statement (architecture tests).
   `PublicApiMatchesInternalIT` puts the two doors side by side for members with different profiles, field rights and a
   private sharing default and requires the same records, fields, refusals and words.
3. **The closed table of V1 operations.** Anything not in the table is not mapped under the prefix.

   | Operation | Address | Notes |
   |---|---|---|
   | `listObjects` | `GET /objects` | the objects the caller may read; not an object a platform module owns |
   | `describeObject` | `GET /objects/{object}` | the fields the caller may read, which they may write, the comparisons each takes (read from the query engine, never copied), the record types |
   | `createRecord` | `POST /objects/{object}/records` | optional `Idempotency-Key` |
   | `getRecord` | `GET /objects/{object}/records/{id}` | `ETag` is the version |
   | `updateRecord` | `PATCH /objects/{object}/records/{id}` | a merge; the version in `If-Match` |
   | `deleteRecord` | `DELETE /objects/{object}/records/{id}` | to the recycle bin |
   | `restoreRecord` | `POST /objects/{object}/records/{id}/restore` | already a clean service of `Records` |
   | `queryRecords` | `POST /objects/{object}/records/query` | the closed query model |
   | `bulkRecords` | `POST /objects/{object}/records/bulk` | optional `Idempotency-Key` |
   | `getPublicApiDescription` | `GET /openapi` | the contract document, no sign-in (ADR-0248) |

   **Not public, on purpose:** the setup of metadata and security, files, imports, sharing, saved views, approvals,
   workflows, integrations, credentials and the platform console. Files and imports stream bytes and need quotas, which
   are Sprint 32's; the rest is the administration of the platform, not the data interface. Purging a record for good and
   listing the recycle bin are left out: restore needs only the identifier the caller already holds, and both can be added
   later without breaking anyone. `PublicApiTableIT` requires the controllers and the table (`PublicApiAccess.OPERATIONS`)
   to be the same set.
4. **Standard and custom objects behave identically**: the same paths, the same bodies, the same errors; the object is
   named by its API name (`Employee__c`, `Account`). An object a platform module owns (a user, a profile, an access policy)
   is absent from the list and answers exactly like an object that does not exist, on every operation.
   `PublicApiParityIT` runs one script over a custom and a standard object and compares statuses and shapes.
5. **The tenant is the host and nothing else** (ADR-0017). A body, a header (`X-Tenant`, `X-Tenant-Id`,
   `X-Forwarded-Host` unless the existing setting trusts it), a parameter or the token never chooses or overrides it. A
   token of one organization used on another host is refused by the existing membership gate. The platform host and an
   organization host that does not exist both answer `NOT_FOUND`, in the same words
   ("No organization exists at this address."). `PublicApiAccessIT` forges the headers on every operation.
6. **What a caller can learn by probing.** An unknown object, an object the caller may not read and an object of another
   organization answer with the same status, code and words; a record the caller cannot see answers like one that does not
   exist; a field the caller may not read is absent and writing it answers like writing a field that does not exist. The
   words come from the one gate, so they cannot drift.
7. **Bearer token only.** The public API is for programs. The session cookie is never turned into a token for the
   prefix (and a browser does not even send it there: its path is the internal one), so there is no cookie to forge a
   request with and no forgery check to ask for. A request without a valid bearer token is `UNAUTHENTICATED`.
8. **No static API keys** (ADR-0006). Nothing in the public API takes a long-lived secret. Client registration, scopes,
   webhooks, quotas and rate-limit headers are Sprints 31 and 32; where the contract must mention them it says "a later
   release". The existing limits per organization (concurrent requests, statement times, ADR-0108) apply to the public
   routes.
9. **Places where the internal API's blanket rules silently did not cover the new prefix, found and closed.**

   | Place | What it did | Now |
   |---|---|---|
   | `TenantResolutionFilter` | resolved the tenant only for `/api/v1/` | `ApiPaths.isApiPath` (both prefixes) |
   | `DefaultOrganizationLimits` | counted turns only for `/api/v1/` | `ApiPaths.isApiPath` |
   | `EmbeddedCors` | would have given the sandbox origin `null` a CORS answer anywhere | none for the public prefix (ADR-0249) |
   | `SecurityConfiguration` | CSRF and the cookie-to-token filter treated the prefix like a browser path | the prefix is exempt from both, bearer only |
   | `RecordBodyLimit`, `BulkBodyLimit` | bounded bodies for `/api/v1/objects/**` only | `PublicApiEdgeFilter`: announced and counted, per setting |
   | `EndpointExposureIT` | walked `/api/v1/` mappings only | walks the public prefix too |
   | `springdoc.paths-to-match` | `/api/v1/**` | unchanged on purpose; the public contract is generated by a run pointed at its prefix (ADR-0248) |
   | `EmbeddedAccessPolicy` | allow-list of `/api/v1/` paths | already fail-closed: a script's token never reaches the prefix (tested) |

## Consequences

A customer can integrate without being broken by a screen change. The cost is a second small surface to keep stable,
bounded by the closed table and by the contract tests of ADR-0247.
