# ADR-0249: The access seam for scopes, no cross-origin rule, and what Sprint 30 leaves out (S30)

- **Status:** Accepted
- **Date:** 2026-10-31
- **Sprint:** S30
- **Related:** [ADR-0006](0006-no-static-api-keys.md), [ADR-0011](0011-api-conventions.md), [ADR-0127](0127-custom-page-scripts-and-the-sandboxed-iframe.md), [ADR-0244](0244-the-public-api-its-address-its-closed-table-and-the-rules-that-carry-over.md)

## Decision

1. **One class decides what a token may reach of the public API: `PublicApiAccess`** (identity module, the analogue of
   `EmbeddedAccessPolicy`), held in front of the controllers by `PublicApiAccessFilter` right after the token is checked, so
   a refusal is the ordinary `403` with the request ID and nothing downstream has been touched. It owns the closed table of
   operations, each with the scope it will need. Today a token of a signed-in user carries no API scope and reaches every
   operation of the table; tomorrow a token that carries scopes reaches only the operations whose scope it holds
   (`records.read`, `records.write`, `objects.read`). **Scope is necessary, never sufficient**: the answer of this class only
   lets a request go on to the controller, and the member's own permissions, field rights and record security still decide
   there (Sprint 31's exit criterion is that a scope-restricted token cannot exceed the user who granted it).
2. **The attribute is `api_scopes`, not `scope`.** The tokens of the web application already carry the standard `scope`
   claim for their sign-in; it says nothing about the public API, and reading it would have refused every user. The tests
   show a token with `scope` and none with `api_scopes` reaches the whole table.
3. **A custom page's script never reaches the public API.** Its token is checked by `EmbeddedAccessPolicy`, whose
   allow-list lists `/api/v1/` paths only, so the prefix is refused (`403`) without any change to that policy (tested).
4. **No cross-origin rule at all.** The public API is for servers. A browser keeps calling its own origin (the internal
   interface), and the sandboxed pages keep their narrow rule, which no longer applies to the public prefix. Allowing
   a browser origin later is a decision with a per-client list of allowed origins, made when clients exist (Sprint 31),
   never a wildcard and never with credentials.
5. **What Sprint 30 leaves out, on purpose.**

   | Left out | Why |
   |---|---|
   | Client registration, client secrets, scopes on tokens, token exchange | Sprint 31; the seam is ready |
   | Webhooks, quotas and rate-limit headers | Sprint 32; the existing per-organization limits apply and answer `429` |
   | Files, imports, sharing, saved views, approvals, workflows, setup, the console | not the data interface (ADR-0244) |
   | Purge for good and listing the recycle bin | restore needs only the identifier; both can be added without breaking anyone |
   | A `GET` with simple filters | a second parser for no gain over `POST .../query` |
   | `createdById` and `updatedById` in the record | the internal record does not carry them either; addable later |
   | A developer portal, a screen in `platform-web` | Sprint 31 has the connected-application screens |
   | A published stability window for individual fields | the whole-version policy of ADR-0247 is the promise |

## Consequences

Sprint 31 fills one map and one attribute; nothing else about the door changes.
