# D2 spike: authentication-provider abstraction

- **Date:** 2026-10-01 (Sprint 0)
- **Status:** Complete. Throwaway code, **not** production code, not part of the build.
- **Code:** `platform/spikes/d2-auth-provider/` (own POM; run with
  `./mvnw -f spikes/d2-auth-provider/pom.xml test` from `platform/`)
- **Decision it supports:** [ADR-0005](../adr/0005-authentication-approach.md); also evidence for
  [ADR-0006](../adr/0006-no-static-api-keys.md)

## Questions and results

All 15 spike tests pass on Java 25.0.4 with Spring Boot 4.1.1 (Spring Security 7.1.1).

| # | Question | Evidence (test) | Result |
|---|----------|-----------------|--------|
| 1 | Can one platform abstraction host local credentials, a federated provider and an MFA challenge behind Spring Security? | `ProviderAbstractionTest` (4 tests) | **Yes.** One adapter (`PlatformProviderAdapter`) serves all three providers; adding the federated stand-in and the MFA stub required no adapter change. A challenge surfaces as its own exception type so the login flow can branch. |
| 2 | Do the failure modes look identical to a caller while staying distinguishable for audit? | `ProviderAbstractionTest.everyFailureLooksIdentical...` | **Yes.** Unknown account, wrong password, disabled, locked and "no linked account" all produce the same `BadCredentialsException` with the same message; the five distinct reasons are recorded in the audit sink in order. |
| 3 | Hashing, enumeration and brute-force protection | `LocalCredentialsTest` (8 tests) | **Feasible as designed.** Argon2id for new hashes with per-hash salt; legacy bcrypt hashes verify and are upgraded to Argon2id on login; unknown accounts pay the same verification work (one verification call, checked by a counting encoder) and a median-timing comparison stays within a generous 0.4x to 2.5x band; account locks after 5 failures (even the right password is refused while locked), recovers after the window, resets the counter on success; lockout duration grows with continued attack and is capped; identifiers are normalised. |
| 4 | Can all of a user's stateless tokens be revoked immediately? | `TokenRevocationTest` | **Yes, with a security-version claim.** Tokens carry `ver`; a validator compares it with the user's current version; suspending the user (or password change, "sign out everywhere") bumps the version and every outstanding token is rejected; a newly issued token works. |
| 5 | Does the authorization server run on this Spring Boot / Java 25 combination, and is revocation of machine tokens supported? | `AuthorizationServerOnJava25Test` | **Yes.** Client-credentials token issued; introspection reports active; after RFC 7009 revocation introspection reports inactive; a wrong client secret returns 401. Used opaque (reference) tokens for the machine client. |

## What the spike does not prove (be honest about limits)

- It does not exercise the **interactive sign-in flow** (authorization code with PKCE, login form, sessions).
  Sprint 3 must prove that the adapter works inside that flow, not only through `ProviderManager`.
- Timing tests are coarse guards against early returns, not a side-channel analysis.
- Argon2 parameters are library defaults. They are validated only to be within 5 ms and 3 s on this development
  machine, not tuned for production hardware.
- Accounts and tokens are in memory; persistence, multi-instance behaviour and Redis are not exercised.
- The federated provider is a stub with a link table; no real OIDC or SAML exchange.
- Spring Authorization Server's in-memory and JDBC authorization stores have no "find by user" query, so
  "revoke all of a user's refresh/reference tokens" needs custom storage or the version approach above.

## Findings that shape Sprint 3

1. **Keep the abstraction small.** Three outcomes (authenticated, rejected, challenge) were enough.
2. **One adapter, uniform errors.** Never let a provider produce caller-visible errors.
3. **Do the dummy verification on every path** (unknown, locked, disabled) so timing does not reveal state.
4. **Two revocation mechanisms are needed:** the authorization server's own revocation for opaque tokens, and
   a user security version for self-contained tokens. Decide the user-token format in Sprint 3 (opaque tokens
   revoke instantly but need a lookup per request; JWTs are cheaper per request but need the version check,
   which should read a cached value, for example from Redis, to avoid a database hit per request).
5. **Argon2 needs an extra cryptography library** (a provider jar not managed by the Spring Boot BOM). The
   version must be pinned in the parent POM and covered by dependency scanning.
6. **The authorization-server DSL changed in Security 7** (`http.oauth2AuthorizationServer(...)`); older
   tutorials do not apply.
7. **Dependency scanning pays off immediately:** the first scan of the application image found fixable
   high and critical vulnerabilities in dependency versions managed by the Boot BOM (see ADR-0004, point 9).

## Stories for Sprint 3 (security)

Each story needs tests of the allowed, denied and abusive cases, and an audit event.

**Password hashing and policy**
- S3-SEC-01 Hash passwords with Argon2id, per-hash salt, parameters chosen by measurement on production-like
  hardware (target single verification in a defined window, documented in an ADR).
- S3-SEC-02 Store the algorithm in the hash so parameters can change; upgrade hashes at login (spike-proven).
- S3-SEC-03 Password policy: minimum length, maximum length (bounded to prevent hashing abuse), no composition
  theatre, check against a list of known-breached passwords, no reuse of the current password.
- S3-SEC-04 Never log, return or audit password material; redact in error messages and request logging.

**Account enumeration protection**
- S3-SEC-05 Uniform sign-in failure response (body, status, headers) for unknown, wrong password, locked,
  disabled and unverified accounts.
- S3-SEC-06 Constant-work verification on every path (spike-proven); timing test kept as a regression guard.
- S3-SEC-07 Same uniformity for password-reset requests, email-verification resends and sign-up ("an email was
  sent if the address exists"); decide how sign-up reveals existing addresses (Sprint 4).

**Brute-force protection**
- S3-SEC-08 Progressive lockout per account (spike-proven) with a cap and automatic recovery.
- S3-SEC-09 Rate limiting per source address and per identifier, shared across instances (Redis), so one
  attacker cannot lock out a victim indefinitely or spray passwords across accounts.
- S3-SEC-10 Define behaviour under distributed attack: lockout must not become a denial-of-service tool;
  decide notification of the account owner.
- S3-SEC-11 Audit every failure with its true internal reason (provider id and reason, never the password).

**Token and session lifecycle, revocation**
- S3-SEC-12 Decide and document the user access-token format and lifetime (short-lived), refresh-token policy.
- S3-SEC-13 Refresh-token rotation with reuse detection: a replayed refresh token revokes the whole family.
- S3-SEC-14 Per-user security version, bumped on password change, suspension, deactivation and "sign out
  everywhere"; validated on every request from a cached source with a defined staleness bound.
- S3-SEC-15 Sign-out revokes the presented tokens at the authorization server.
- S3-SEC-16 Signing-key management: key identifiers, rotation, retirement; keys never in the repository or
  in metadata tables (secrets interface, ADR-0007).
- S3-SEC-17 Authorization-record storage in the database with an index by user to support
  revoke-all-for-user and administrative session listing.
- S3-SEC-18 Cookie and session flags (secure, HTTP-only, same-site), CSRF protection for the interactive
  login, and PKCE required for public clients.

**Abstraction and integration**
- S3-SEC-19 Productionise the provider abstraction and single adapter; keep the OIDC/SAML extension point
  documented but unimplemented.
- S3-SEC-20 Prove the adapter inside the interactive authorization-code flow, not only through the provider
  manager.
- S3-SEC-21 Tenant context is not decided at sign-in by the browser: the token identifies the user, and the
  active tenant is derived from membership (Sprint 5 switching); never accept a tenant identifier from the client.
- S3-SEC-22 Authentication audit events (success, failure, lockout, token issue, revoke, sign-out) written
  through the audit module contract once it exists (audit v0 may be a stub until Sprint 9).
