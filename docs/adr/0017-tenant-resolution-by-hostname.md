# ADR-0017: Tenant resolution by host name

- **Status:** Accepted
- **Date:** 2026-10-01
- **Sprint:** S2

## Context

The architecture gives each organization a host name, `<slug>.<platform-domain>`, with records addressed by path beneath it.
The host is a routing mechanism: it tells the platform which organization a request is for. It is not an authorization, and
it is not the organization's permanent identity (custom domains will point other names at the same organization).

## Decision

1. **Where the host comes from.** The `Host` header as the container reports it. Only when the deployment says a trusted
   proxy sits in front (`PLATFORM_TRUST_FORWARDED_HOST=true`, default **false**) is the first value of `X-Forwarded-Host` used
   instead. The switch is on in the `local` profile because the development proxy of the web app rewrites the `Host`
   header; a deployment switches it on only where its ingress overwrites the header on every request. With the switch on and
   the API reachable directly, a caller could name any organization host, so the default is off. (Decision of the project
   owner, recorded in ADR-0014.)
2. **The platform domain** is configuration, `PLATFORM_BASE_DOMAIN`, mandatory in every deployed environment (no default, so
   a deployment cannot silently run with the wrong one). The `local` profile uses `localhost`, so `tenant-a.localhost` works
   in a browser without editing any file; tests use `platform.example.test`.
3. **Reading a host.** Normalized (lower case, port and trailing dot removed; anything that is not a plain DNS name is a
   foreign host). Then:
   - the base domain itself and the reserved names under it are the platform's own hosts: a request **without a tenant**;
   - `<valid slug>.<base domain>` is an organization host;
   - anything else under the base domain (two labels, a malformed label) can never be an organization: `NOT_FOUND`;
   - a host outside the base domain is foreign: no organization is resolved (direct access by address, probes). This is the
     one place where custom domains plug in later: a lookup in a table of verified domains.
4. **Answers.** An unknown organization: `NOT_FOUND` (404). An organization that exists but is `SUSPENDED`, `DEACTIVATED` or
   still `PROVISIONING`: `TENANT_UNAVAILABLE` (403) with one generic message, so the answer never says which state it is
   in. Both in the platform's error model with the request ID. The host name is client input and is not logged or echoed. The
   refusal applies to **every** `/api/v1` path, not only the tenant endpoint, so a closed organization cannot reach any
   endpoint through its host.
5. **The tenant comes from the host and nowhere else.** Headers such as `X-Tenant-ID`, query parameters, path segments and
   bodies are never consulted. Tests send them naming another organization and show they change nothing.
6. **Where it runs.** A servlet filter of the `tenant` module, just inside the request-correlation filter: it opens the
   tenant context for the whole request (before any transaction, ADR-0014), answers refusals through the platform's single
   exception handler, and leaves the tenant ID on the request so the access record written by the outer filter names it.
   Requests to platform hosts pass through without a tenant; a platform endpoint needs none, and tenant data is invisible
   without one (ADR-0015).
7. **Lookup.** One indexed query by slug per request, not cached. A status change therefore takes effect on the next request.
   A cache (with its staleness window) is a decision for later and part of the Sprint 14 benchmark question.
8. **The endpoint.** `GET /api/v1/tenant/current` returns the slug and display name of the resolved organization (no
   identifier), so a page can show whose address it is; `NOT_FOUND` when the host addresses no organization.
9. **From Sprint 5** the organization resolved here must also match a membership of the authenticated identity; resolving a
   host never grants access.

## What was verified

`HostMatcherTest` (36 cases: case, port, trailing dot, reserved names, nested and malformed labels, look-alike suffixes,
address literals); `TenantResolutionIT` (served, unknown, suspended, deactivated, provisioning, platform hosts, forged headers,
parameters and body, status changes taking effect at once, no identifier in the answer); `TenantResolutionForwardedHostIT` (the
switch, first value of a chain, fallbacks); `StructuredLoggingIT` (tenant ID in the access record, host not logged). The
container itself refuses an invalid host name with 400 before the platform sees the request. Verified on the running stack
through the web app's development proxy: see the Sprint 2 document.

## Consequences

- Hostname routing works on a developer machine with no configuration beyond a name ending in `.localhost`.
- A wrongly configured trust switch is the one way to let a caller choose the organization host; the deployment design
  (Sprint 33) must set it only behind the ingress.
- Reserved names cannot be taken by organizations, leaving room for platform sites.

## Alternatives considered

- **A tenant header or a path segment:** trivially forged and repeated in every client; rejected by the standing rule.
- **Always trusting `X-Forwarded-Host`:** works behind a proxy, wrong when the API is reachable directly.
- **Resolving the tenant only after authentication:** needed too (Sprint 5), but anonymous pages (sign-in, branding) need the
  organization before anyone is known.
- **A wildcard match on the whole host:** hides which part is the organization; the single label under the base domain is
  explicit and testable.

## References

- `platform-app/src/main/java/app/platform/tenant/internal/` (`HostMatcher`, `TenantHostResolver`, `TenantResolutionFilter`,
  `TenancyProperties`, `TenantController`)
- `platform-app/src/test/java/app/platform/tenant/`
- Architecture notes: tenant-scoped host names, custom domains later, hostname as a routing mechanism (note 2).
