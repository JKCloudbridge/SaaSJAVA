# ADR-0011: API conventions: versioning, envelope, errors, paging, contract

- **Status:** Accepted
- **Date:** 2026-10-01
- **Sprint:** S1
- **Extends:** [ADR-0001](0001-modular-monolith.md) with the `web` module (infrastructure of the HTTP layer)

## Context

Every later sprint adds endpoints, and the frontend and, from Sprint 30, outside developers consume them. If each
endpoint answers in its own shape, every client needs special cases. The backend is authoritative and must never leak
internals; the browser must be able to react to failures by code, not by parsing text; and the frontend's types must not
be able to drift from the API.

## Decision

1. **Versioned paths.** Every platform endpoint lives under `/api/v1` (`ApiPaths`). A breaking change introduces `/api/v2`
   next to it. Probes and metrics are not part of the API: they are served on a separate management port.
2. **One envelope** (types in `platform-api-contract`, all implementing `ApiEnvelope`):
   - one result: `{"data": {...}}` (`ApiResponse`);
   - a collection: `{"data": [...], "pagination": {"limit", "hasMore", "nextCursor"?}}` (`ApiPageResponse`);
   - any failure: `{"error": {"code", "message", "fields"?, "requestId", "traceId"?}}` (`ApiErrorResponse`).
   A field that is null is left out of the JSON (so the generated client types it as optional). An architecture test
   fails the build when a controller endpoint returns anything other than an envelope type, a `ResponseEntity` or nothing.
3. **One error model with stable codes** (`ErrorCode`): `VALIDATION_ERROR`, `MALFORMED_REQUEST`, `UNAUTHENTICATED`,
   `FORBIDDEN`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `CONFLICT`, `CONCURRENT_MODIFICATION`,
   `PAYLOAD_TOO_LARGE`, `UNSUPPORTED_MEDIA_TYPE`, `RATE_LIMITED`, `INTERNAL_ERROR`, `SERVICE_UNAVAILABLE`. Each code has one HTTP
   status and a generic message. Clients branch on the code. A code is never renamed or reused (a test freezes the list);
   adding one is allowed, and the frontend stops compiling until it handles it.
4. **Nothing internal reaches a client.** One `@RestControllerAdvice` (module `web`) turns every failure into the error
   model. A module fails a request on purpose by throwing `ApiException`, whose message and fields are written for the
   client. Everything else (framework messages, parser errors, SQL errors, any other exception) is replaced by the code's
   generic message; unexpected failures answer `INTERNAL_ERROR` and are reported through the error tracking hook
   ([ADR-0012](0012-observability.md)). Validation errors name the field and the constraint, never the rejected value. Failures
   outside the controllers (the container's error dispatch) use the same model. Tests feed every kind of failure with
   messages full of secrets and personal data and assert none appears in the response.
5. **Request ID.** `X-Request-ID` is accepted from the client when it is 8 to 64 characters of letters, digits, dot,
   underscore or hyphen; otherwise a new one is generated (`req_` plus 22 characters, time-ordered) and the client's value is
   not echoed. It is returned on every response, in every error body and in every log line. `X-Trace-Id` returns the trace.
6. **Cursor pagination.** `?limit=` (default 50, at most 200) and `?cursor=` (opaque, at most 512 characters).
   Out-of-range or malformed values are validation errors naming the parameter; they are never silently clamped. A cursor
   is not signed: it can only move the caller inside results they may already see, because every page is authorized again,
   so producers must never put anything in it the caller may not know. Offset paging is not offered; sorting parameters
   arrive with the query engine (Sprint 16).
7. **Validation.** Bean Validation on request bodies and parameters; a body that cannot be read is `MALFORMED_REQUEST`. The
   frontend repeats validation only for convenience: the backend decides.
8. **The OpenAPI document is the contract.** It is generated from the controllers and the contract types, committed as
   `platform-api-contract/src/main/resources/openapi/platform-api-v1.json` and compared with the running application by
   `OpenApiContractIT`: a controller change without the regenerated document fails the integration build
   (`scripts/update-openapi.ps1` or `.sh` regenerates it). Every operation documents the error model as its `default`
   response; the document names no host and contains no timestamp, so it is identical everywhere. It is served at
   `/api/v1/openapi` in local and test, and **not** in `prod` (publishing it is a decision of Sprint 30). No interactive
   documentation page is shipped.
9. **The TypeScript client is generated from the committed document**, committed, and checked in CI (`npm run api:check`
   fails when it differs from a fresh generation). Hand-written frontend code uses only the generated types.
10. **No CORS.** The browser calls its own origin; the Next.js server (and in a deployment the ingress) forwards `/api` to
    the API. This removes a class of misconfiguration and keeps cookies first-party. The public API (Sprint 30) will
    decide its own cross-origin rules.
11. **Module.** `web` (`app.platform.web`, allowed dependencies: `sharedkernel`, `observability`) holds the error handling,
    the paging argument binding, the OpenAPI configuration and the platform status endpoint
    (`GET /api/v1/platform/status`: success means the API is up and a database query ran). Business modules use only the
    contract types and `ApiException`; an architecture test forbids them from depending on `web`.

## Consequences

- Every endpoint is typed end to end; the frontend cannot compile against a stale contract.
- A deliberate API change is a visible diff in three committed files (controller, document, generated types).
- Error handling is centralized, so a new failure kind is one change in one place.
- Public API versioning policy, deprecation, rate limiting and the developer portal are later work (Sprints 30 to 32).

## Alternatives considered

- **The standard problem-details format for errors:** a fine standard, but it has no place for the request ID, trace ID or
  per-field problems without extensions, and the platform wants one model for every consumer.
- **A response advice that wraps every return value automatically:** hides the type from the generated document and from
  readers; explicit envelope types are checked by the compiler and by the architecture test.
- **Contract-first (hand-written document, generated controllers):** more ceremony, and the controllers are where changes
  are made; the committed-document test gives the same protection.
- **Offset or page-number paging:** simple, but skips and repeats rows when data changes and degrades on large tables.

## References

- `platform-api-contract/src/main/java/app/platformapi/` (envelope, errors, paging, paths, headers) and
  `.../resources/openapi/platform-api-v1.json`
- `platform-app/src/main/java/app/platform/web/`
- `platform-app/src/test/java/app/platform/web/` (`ApiConventionsTest`, `ApiOverHttpIT`, `OpenApiContractIT`)
- `platform-web/src/lib/api/` (generated client and wrapper), `platform-web/scripts/api-client.mjs`
- Architecture notes: REST and OpenAPI, error model, request ID, pagination (phase 8); frontend error handling (phase 5)
