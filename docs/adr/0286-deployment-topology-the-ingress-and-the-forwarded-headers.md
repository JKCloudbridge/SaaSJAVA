# ADR-0286: Deployment topology, the ingress, and the forwarded headers (S33)

- **Status:** Accepted
- **Date:** 2026-11-24
- **Sprint:** S33
- **Related:** [ADR-0017](0017-tenant-resolution-by-hostname.md), [ADR-0021](0021-brute-force-protection-and-rate-limits.md), [ADR-0108](0108-limits-per-organization.md), [ADR-0287](0287-images-the-build-stamp-and-the-tag-rule.md), [ADR-0289](0289-zero-downtime-releases-the-drain-and-the-threshold-of-the-proof.md), [ADR-0291](0291-environments-the-matrix-outbound-addresses-and-what-staging-may-do.md)

## Context

The host name is the tenant, so the ingress is a security component. Reading the code against the transition plan found two
places where the plan's picture of the cloud load balancer is wrong:

- **The load balancer appends to `X-Forwarded-For`; it does not overwrite it.** `ClientSource` takes the *first* entry when
  `PLATFORM_TRUST_FORWARDED_FOR` is on, and its documentation and test say "only behind a proxy that overwrites". Behind an
  appending balancer the first entry is whatever the client wrote, so a caller could choose its own source address: it could
  spread failed sign-ins over any number of "sources" and never meet the per-source limit, and write a false source into the
  audit trail.
- **The load balancer never sets `X-Forwarded-Host` and passes a client's one through.** With `PLATFORM_TRUST_FORWARDED_HOST`
  on, a client could name another organization's host in that header. The balancer does preserve the original `Host` header,
  which is what the tenant is taken from.
- **It cannot bound a body with no announced length.** The application bounds a body by the length the client announces
  (Sprint 14) and leaves the rest to the proxy; the balancer has no body limit and no time limit per kind of request.

## Decision

1. **One host, one ingress, path rules.** The load balancer terminates https (one wildcard certificate for the platform domain
   and every name under it), redirects http, sends HSTS, preserves `Host`, and routes `/api/*` to the application and everything
   else to the web server. The web pages and the API of an organization are the same host: first-party cookies, no CORS. `/actuator`
   is answered 404 by a rule of its own. Port 8081 (probes and metrics) is opened to nobody.
2. **An edge sidecar in every application task** (`deploy/edge`, a reverse proxy image with the platform's rules baked in) sits
   between the balancer and the application. The balancer's target for `/api` is the sidecar on port 8080; the application listens
   on 127.0.0.1:8090 (`PLATFORM_HTTP_PORT`). The sidecar: takes the caller's address as the first address outside the balancer's
   network, writes it as the one value of `X-Forwarded-For`, deletes any `X-Forwarded-Host`, sets a **body size limit that also
   holds for a chunked body** and a **time limit** for each kind of request. The kinds and their numbers are in one file,
   `deploy/edge/api-rules.caddy`, which is also imported by the rehearsal's ingress, so what the rehearsal proves is what runs.
3. **`PLATFORM_TRUST_FORWARDED_HOST=false` in every deployment behind the cloud balancer.** `Host` is preserved, so nothing is
   lost. It is true only behind an ingress that overwrites the header, which this topology has none of.
4. **`PLATFORM_TRUST_FORWARDED_FOR=true` with `platform.deployment.forwarded-for-hops=1`** (a deployment-only setting, `prod`
   profile, default 0 elsewhere). `ForwardedForNormalizer` replaces the header by the entry that many places from the right (the
   one the trusted hop added) before any other filter reads it, so the existing reader, the first entry, sees exactly one honest
   value. This is defence in depth beside the sidecar and keeps the application correct if a future ingress appends. With 0
   (a developer machine, the tests) it does nothing.
5. **Rejected:** the balancer alone (cannot do the three things above); a proxy container in front of the balancer (a hop more
   to run and scale, with the same result); changing `ClientSource` to read the right-most entry always (changes what an
   overwriting proxy's operators rely on, and a normalizer reaches every reader at once).

## Consequences

- Each application task runs three containers (sidecar, application, metrics collector) and the application depends on the
  sidecar, so a stop drains the application first and the sidecar last ([ADR-0289](0289-zero-downtime-releases-the-drain-and-the-threshold-of-the-proof.md)).
- The transition plan's sentence that the balancer "overwrites `X-Forwarded-For` and `X-Forwarded-Host`" is corrected there.
- The rehearsal's ingress appends to `X-Forwarded-For` and passes a forged `X-Forwarded-Host` through on purpose; `checks.ps1`
  proves that neither changes the organization or the source the application sees.
- Rate limits at the edge are the application's own (Sprint 32) plus the size and time limits here; a web application firewall
  is not part of this sprint.
