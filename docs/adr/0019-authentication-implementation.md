# ADR-0019: Authentication implementation: sign-in, sessions, tokens, revocation and keys

- **Status:** Accepted
- **Date:** 2026-10-01
- **Decision IDs:** D2 (implementation)
- **Sprint:** S3
- **Implements:** [ADR-0005](0005-authentication-approach.md). Related: [ADR-0020](0020-passwords-and-hashing.md),
  [ADR-0021](0021-brute-force-protection-and-rate-limits.md), [ADR-0022](0022-platform-level-identity-tables-and-audit-v0.md)

## Context

ADR-0005 chose the authorization server and a provider abstraction, and left the shape of the user session open: the format and
lifetime of the access token, the refresh policy, how revocation works, where the keys live, how the Next.js application holds the
session. Sprint 3 had to decide these and prove them. The user (the product owner) answered four questions at the start of the
sprint and accepted the recommendations: the **standard flow with opaque tokens** (authorization code with PKCE, tokens only in
HttpOnly cookies, the database as the truth), **sign-in on organization hosts and on the platform host**, **audit v0 written in the
same transaction**, and **degrade, do not stop, when Redis is down** (the last two are recorded in ADR-0021 and ADR-0022).

Facts that shaped the design: a revoked or signed-out token must stop working on the next request on every instance; the browser
must never hold a token a script can read; the tenant comes from the host name only (ADR-0017) and users are global identities with
no memberships yet (Sprint 5); the application runs as an unprivileged database role; instances are stateless.

## Decision

1. **The flow.** The sign-in is two steps, and the second is the standard authorization-code flow with PKCE against the platform's own
   authorization server (Spring Authorization Server):
   1. `POST /api/v1/auth/sign-in` checks the address and the password through the provider abstraction and, on success, creates a
      **login session** (a database row; its random secret travels in the cookie `platform_login`, only the hash is stored) that is
      valid for five minutes. It is not an API credential; only the authorization endpoint reads it.
   2. The browser then navigates to `GET /api/v1/auth/start`, which makes a one-time state and a PKCE verifier (kept in the cookie
      `platform_tx`) and redirects to `/api/v1/oauth2/authorize` with the challenge. The authorization endpoint learns who signed in
      from the login cookie (a `SecurityContextRepository` that reads the database; there is **no server-side HTTP session**, so any
      instance can serve any step), and answers with a one-time code to this host's `/api/v1/auth/callback`.
   3. The callback checks the state, exchanges the code with the verifier **in-process** on the library's own authentication
      providers (same rules, storage and token generator as the open token endpoint) and sets the session cookies.
2. **Tokens.** Access tokens and refresh tokens are **opaque random references** (768 bits), not signed documents. Access token life
   **10 minutes**. A refresh token is **replaced on every use**; it lives **8 hours** from issue (so 8 hours idle) and a sign-in ends
   after **30 days** in total whatever the refreshes. ID tokens (scope `openid`) are signed documents, issued because the standard flow
   issues them and published keys are required (decision 8); nothing in the platform relies on them.
3. **Where the tokens live: in HttpOnly cookies, nowhere else.** `platform_at` (access token, path `/api/v1`), `platform_rt` (refresh
   token, path `/api/v1/auth`, sent only to refresh and sign-out), `platform_login` (path `/api/v1/oauth2/authorize`), `platform_tx`
   (path `/api/v1/auth/callback`). All are `HttpOnly`, `SameSite=Strict` and `Secure` outside the local profile. No script on the page
   can read them, and the page keeps nothing in `localStorage` or `sessionStorage` (verified in a real browser). Programs other than
   the web app send the access token as `Authorization: Bearer`.
4. **The database is the source of truth, read on every request.** One table, `oauth2_authorization`, holds one row per sign-in grant
   (code, access token, ID token, refresh token) with only **SHA-256 hashes** of the secrets, an index by user, the host the grant is
   bound to, the user's security version at issue and a revocation mark. A token is **alive** when its row is not revoked, is within
   its absolute lifetime, its user has status `ACTIVE`, **and the user's security version equals the one the grant was issued under**:
   one query decides all of that, so there is no cache that can be stale on another instance. The staleness bound of story S3-SEC-14
   is therefore **zero**; the cost is one indexed lookup per request, to be measured in the Sprint 14 benchmark (gate G1), which
   decides whether a cache with a stated bound is worth having.
5. **Revocation.** The user has a `security_version` that only rises. It rises when the user leaves `ACTIVE` (a database trigger does
   this even for a hand-written statement), on a password change or reset, and on "sign out everywhere". Grants and login sessions are
   also marked revoked so the tables stay truthful and listable. Sign-out revokes the grant of the presented access token and of the
   refresh cookie and removes the cookies; "sign out everywhere" revokes every grant of the user. Two real application instances prove
   it (`TwoInstancesIT`): a token revoked through one instance is dead on the other on its next request.
6. **Refresh rotation and reuse detection (S3-SEC-13).** The store keeps the hash of the refresh token that was replaced and when. A
   replayed replaced token ends the whole grant and is audited, **unless it was replaced only seconds ago** (grace, default 10 s): two
   browser tabs that refresh together must not look like theft, so that case is refused without ending the sign-in and **without
   clearing the cookies** the winning request has just set. Two simultaneous refreshes of one token have exactly one winner (the new
   refresh token is stored only if the stored one is still the one the request started from).
7. **A grant is bound to the host it was issued on** (an organization, or the platform host). Presented elsewhere it is refused and the
   attempt audited. This is a stop-gap until the membership check of Sprint 5; it also makes the redirect rule below meaningful.
8. **Signing keys (S3-SEC-16).** ID tokens are signed with P-256 keys that carry an identifier. A deployment supplies the keys as
   files (PKCS#8 private, X.509 public) from its secret store, **newest first**; the first signs, all are published, so a key is rotated
   by putting a new one in front and retired by removing the old one after the longest token life. With no key a deployment refuses to
   start. The local profile and the tests generate one key in memory (nothing is written anywhere). No key is in the repository or in
   any table (ADR-0007); a secrets-store implementation of the key provider comes with Sprint 27.
9. **Forgery protection (S3-SEC-18).** A request that changes something and is authenticated by cookie needs the header
   `X-XSRF-TOKEN` equal to the cookie `XSRF-TOKEN` (readable by the page on purpose; the attacker's page can make the browser send the
   cookie but cannot read it). A request that carries an `Authorization` header is exempt, because another site cannot set it. **A
   regression found and fixed during the sprint:** the framework exempts any request that "has a bearer token" and decides that with
   the same resolver that reads tokens; the first version of the resolver also read the cookie, so every cookie-authenticated request
   was exempt. The resolver now reads the header only and a separate filter turns the cookie into a header **after** the check; a
   test (`ForgeryProtectionIT`) posts to every state-changing endpoint with only the cookie and expects 403. That filter skips the
   public endpoints, so a browser holding a revoked cookie can still sign in again (a second bug found by a test).
10. **Where the code may go (S3-SEC-18).** A fixed redirect list cannot work with one host per organization, so the authorization
    endpoint accepts only this host's callback: same host name as the request, exactly the callback path, no user information, query or
    fragment, and the scheme the platform uses. The page the browser lands on afterwards is a path on this site only (otherwise the
    home page). PKCE is required, `S256` only.
11. **Refresh tokens and the public client.** The library issues refresh tokens only to a client that is not public. The web client is
    registered as public (PKCE, no secret), and the platform's own backend, which makes the exchange for the browser, presents itself
    to the library as an internal client method. The open token endpoint therefore issues an opaque access token and an ID token but
    **no refresh token** to a public caller; only the backend-for-frontend path yields cookies with a refresh token.
12. **The tenant context.** The user is filled into `TenantContext` from the authenticated identity (a filter after the token check);
    the tenant still comes only from the host. On the platform host there is no tenant, and the context cannot exist without one, so
    the caller is visible there only in the security context (`GET /api/v1/auth/me` reads it). The membership is not looked up.
13. **Provider abstraction (S3-SEC-19).** `PlatformAuthenticationProvider` (`id`, `supports`, `authenticate`) returns `Authenticated`,
    `Rejected` (with an internal reason) or `ChallengeRequired`; one adapter turns it into Spring Security's provider type and is the
    only way a sign-in reaches a provider. OIDC, SAML and a second factor are further implementations plus a new permitted attempt
    type; documented on the interface, not implemented. A test plugs in a second provider without touching the adapter.
14. **Default deny.** Every `/api/v1` path needs a valid token except an explicit list (status, the OpenAPI document, the host's
    organization, the forgery cookie, the sign-in endpoints). `EndpointExposureIT` walks every request mapping of the application and
    fails when one answers an anonymous caller with anything but 401 without being on the list. Refusals (401, 403) are answered
    through the platform's one exception handler, so they carry the request ID and trace ID (the carry-over from Sprint 1).

## Consequences

- A person can sign in on any organization host, and gets no permission from it, until Sprint 5 adds memberships. The only protections
  are the host binding and that no tenant data is exposed to an identity alone.
- After a refresh the old access token is dead at once; a request that was already in flight with it gets a 401 and the page's next
  call (after the cookies are replaced) succeeds. The page refreshes once on a 401 for its own session check; a general
  retry-on-401 in the API client is left to the first sprint that needs it.
- The authorization store implements the library's contract itself, reusing its JSON mapping of the grant. The library's own JDBC
  store is not usable: it does not follow the table conventions (identifier type, base columns, version-checked updates) and keeps
  secrets in the clear. The library's column order is relied on and is covered by tests that fail on a change after an upgrade.
- Login sessions and ended grants are removed by a clean-up job after seven days (`IdentityCleanup`); a grant stays that long after it
  ends so that a replay of an old token can still be recognised.
- Not measured in this sprint: the per-request cost of the token query (Sprint 14).

## Alternatives considered

- **Self-contained signed access tokens with a security-version check.** Cheap to read, but every request still needs a lookup for
  sign-out of one token and for the version, so the cost is the same and the revocation logic is split in two; rejected.
- **An own session endpoint that sets a cookie directly** (no authorization-code flow). Simpler, but the PKCE and authorization-code
  stories would not apply to the web app, ADR-0005 would change, and machine and third-party clients (Sprint 31) would need a second
  mechanism; rejected by the user's decision.
- **A server-side HTTP session** (in memory, Redis or the database). It would tie sign-in to Redis (the outage decision) or add a
  session store; the login session row does the one job needed.
- **Tokens held by the Next.js server** (a backend-for-frontend in Node). Moves security code out of the Java tests and breaks the
  rule that the frontend is presentation only; the Java backend plays that role instead.

## References

- Code: `platform-app/src/main/java/app/platform/identity/` (public API in the root package, everything else in `internal/`),
  `docs/adr/0005-authentication-approach.md`, `docs/spikes/d2-authentication-provider-spike.md`
- Tests: `AuthorizationCodeFlowIT`, `TokenLifecycleIT`, `TwoInstancesIT`, `ForgeryProtectionIT`, `EndpointExposureIT`,
  `AuthEndpointsAuthorizationIT`, `TenantAndUserContextIT`, `LogsAreCleanIT`, `SigningKeysTest`, `CallbackRedirectValidatorTest`
- Delivery plan: Sprint S3; decision D2
