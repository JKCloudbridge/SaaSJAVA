# Runbook: the public API

For the person who operates the platform. What the interface is and how a customer uses it is in
[../public-api.md](../public-api.md); the decisions are in ADR-0244 to ADR-0249.

## Settings

| Setting (environment variable) | Default | What it does |
|---|---|---|
| `platform.publicapi.enabled` (`PLATFORM_PUBLIC_API_ENABLED`) | `true` | `false` answers every address under `/api/public/v1`, the contract document included, with `404 NOT_FOUND`, before sign-in is looked at. The internal interface is not touched |
| `platform.publicapi.idempotency.retention` | `24h` | how long a key sent in `Idempotency-Key` keeps its outcome (1 minute to 7 days) |
| `platform.publicapi.limits.max-body-bytes` | `1048576` | the largest body of a create, a change or a query |
| `platform.publicapi.limits.max-bulk-body-bytes` | `8388608` | the largest body of a bulk call |
| `platform.data.bulk.max-records` | `200` | the most records in one bulk call (the public interface shares it) |
| `platform.data.query.*` | see the engineering guide | page size, filter size, `IN` values, the scan limit of an unindexed question |
| `platform.data.idempotency.enabled`, `.interval`, `.batch-size` | `true`, `15m`, `1000` | the clean-up of expired outcomes (housekeeping; an expired outcome is also forgotten when its key is used again) |
| `platform.tenancy.limits.*` | see ADR-0108 | the turns and statement times of one organization, which apply to the public routes |

Every setting has a safe default; the `local` profile starts unchanged. **Deployment requirement:** the proxy in front of the
application should also set a request size limit; the application itself refuses a body past its limit whether or not the
length was announced.

## Switching it off, and back

Set `PLATFORM_PUBLIC_API_ENABLED=false` and restart (or roll) the instances. Customers then get `404 NOT_FOUND` for the
interface and its description; nothing else changes. Nothing is lost: keyed outcomes stay until they expire, and records
written through the interface are ordinary records. Switch it on again the same way. Use this when a defect in the public
interface must be stopped faster than a fix can be shipped.

## Reading it

Metrics (micrometer; the only tags are the operation name of the contract and the class of the status):

- `platform.publicapi.requests{operation,result}`: `result` is `success`, `client_error` or `server_error`. A rising
  `client_error` for one operation is a customer's bug or an attack; a rising `server_error` is ours.
- `platform.publicapi.request.duration{operation}`: the time of each operation.
- `platform.api.errors{code}`: the error model's counter (shared with the internal interface); `RATE_LIMITED` is the organization
  limit being reached, `PAYLOAD_TOO_LARGE` a body over the limit.

Requests refused before they reach an operation (no token, the kill switch, a body too large for the filter) are not in
`platform.publicapi.requests`; they are in `platform.api.errors`. The log line of a request carries the request ID, the
trace ID and the organization, as everywhere. **Nothing a caller typed, no token and no key is ever logged.** To answer "what
happened to this customer's request": ask for the request ID (every answer carries it) and look it up in the log and the
trace; the audit trail of the change is written by the record services, once per change, as for the internal interface.

## When a customer says a retry made two records

It cannot happen for a request that carried an `Idempotency-Key` within 24 hours; check the two requests had the same key, the
same body and the same user, and that they were not more than the retention apart (`idempotency_record` holds an hour-stamped
row for each success; it holds hashes and identifiers only, so reading it is safe). Without a key the platform cannot know.

## Announcing a deprecation

1. Decide the successor and write its address.
2. Add a `Deprecation` to `ApiVersioning.shipped()` with the announcement day, the sunset day (**at least 12 months later**; the
   build refuses less) and the successor.
3. Regenerate the contract (`scripts/update-openapi`): the operation now says `deprecated: true`. Update the baseline only for
   what is added, never to hide a removal.
4. Tell customers (a release note and `docs/public-api.md`); they will also see `Deprecation`, `Sunset` and `Link` on every
   answer of the operation.
5. After the sunset day, remove the operation in the next version only (`/api/public/v2`), or in `v1` if it was never used
   (the metrics above show use); update the baseline in the same change.

## Adding to version 1

Only additively: a new optional property of an answer, a new optional parameter, a new operation, a new error code. Change the
type, regenerate the contract, run `PublicContractBaselineTest` (it passes for an addition), update the baseline file
`platform-app/src/test/resources/openapi/platform-public-api-v1.baseline.json` and add the line to the ADR. A new operation
also goes into `PublicApiAccess.OPERATIONS` with its scope (`PublicApiTableIT` fails until both sides agree).

## Where the data is

`idempotency_record` (migration V090): one row per keyed write that was carried out; forced row level security; never changed;
removed only after it expires. It is the only table of this interface; the records themselves are in the record store.
