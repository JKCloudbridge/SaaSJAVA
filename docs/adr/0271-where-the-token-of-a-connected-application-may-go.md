# ADR-0271: Where the token of a connected application may go (S31)

- **Status:** Accepted
- **Date:** 2026-11-23
- **Sprint:** S31
- **Related:** [ADR-0127](0127-custom-page-scripts-and-the-sandboxed-iframe.md), [ADR-0249](0249-the-access-seam-for-scopes-no-cross-origin-rule-and-what-sprint-30-leaves-out.md), [ADR-0267](0267-a-connected-application-acts-as-the-member-who-registers-it.md)

## Context

Without a rule, the token of a connected application would be a full session of the member it acts as. The internal
interface has several hundred paths, nearly all of them setup and administration that other people's software must never
reach whatever the member could do; the management of connected applications is among them (an application that could
register, rotate or remove applications could keep itself alive after it was switched off).

## Decision

1. **A machine token reaches the public prefix and nothing else.** `ClientTokenAccessFilter` (identity module) is the mirror
   image of `EmbeddedAccessFilter` / `EmbeddedAccessPolicy`, in the same API filter chain right after the token is checked:
   a request made with the token of a connected application is let through only for a path under `/api/public/v1/`, and
   answered with the ordinary **403** of the error model, with the request ID, for every other path. Nothing downstream
   (controllers, the tenant context, the database) has been touched when it refuses.
2. **It is a list of what is allowed, not of what is forbidden.** A path added later is refused until somebody decides. A
   path with an escape, a semicolon, a backslash, a dot segment or an empty segment is refused outright (the web firewall
   already refuses most of them; this is the second line).
3. **What that covers.** The internal interface, client management (`/api/v1/connected-apps`), the other management
   endpoints, `/api/v1/auth/**`, and also the paths that are public for everyone else: the rule looks at the token, not the
   path, so a machine token is refused there too. The token endpoint and the revocation endpoint of the authorization server
   are in another chain and authenticate the client with its secret, never with a token. The description document of the
   public API stays public: it needs no token at all.
4. **Other ways round are closed.** The platform host refuses client credentials (a client is not found there); a forged
   `X-Tenant` or `X-Forwarded-Host` changes nothing (the tenant is the host, ADR-0014); another organization's host does not find
   the client or its grant; a custom page's token and a machine token are different kinds and neither can ask for the other
   (the embedded-token endpoint is inside the internal interface).
5. **It also names the caller.** The same filter puts the public identifier of the application on the request and into the
   logging context for the rest of the request (ADR-0272).

## Consequences

- `ClientTokenBoundaryIT` walks every mapped endpoint of the internal interface with a machine token and expects 403; a new
  controller needs nothing, because the rule is the list.
- A person's own token and the token of a script are unaffected.
