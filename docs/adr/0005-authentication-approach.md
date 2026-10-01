# ADR-0005: Authentication: Spring Authorization Server with local credentials behind a provider abstraction

- **Status:** Accepted
- **Date:** 2026-10-01
- **Decision IDs:** D2
- **Sprint:** S0 (decided and spiked); implemented in S3

## Context

Version 1 needs sign-in with local credentials. Later versions need external identity providers (OIDC
federation, SAML), multi-factor authentication and user provisioning (SCIM). Machine clients need OAuth 2.0
client credentials (ADR-0006). Authentication (who are you) must stay separate from authorization (what may
you do), which is always owned by the platform and evaluated in the active tenant context.

## Decision

1. Use **Spring Authorization Server** (part of Spring Security 7.1) as the token issuer and OAuth 2.0 endpoint
   provider, with **local credentials** (platform-managed password hashes) in V1.
2. Put **a platform authentication-provider abstraction** between the credential check and Spring Security:
   a small interface (`id`, `supports`, `authenticate`) with sealed attempt and outcome types
   (authenticated, rejected with an internal reason, challenge required). **One adapter** converts it to Spring
   Security's own provider type, so the authorization server never sees which provider was used.
3. **Authorization is never delegated to the provider.** Tokens identify the user (and, from Sprint 5, the
   membership); permissions are evaluated by the platform's authorization engine against the active tenant.
4. **Failure responses are uniform.** Unknown account, wrong password, locked and disabled all return the same
   error to the caller; the true reason goes to the audit trail only.
5. Later providers (OIDC federation, SAML, MFA step-up, SCIM) are additional implementations of the
   abstraction, not changes to it.

## Consequences

- The abstraction was validated in a Sprint 0 spike (throwaway code; findings in
  [../spikes/d2-authentication-provider-spike.md](../spikes/d2-authentication-provider-spike.md)): a local
  provider, a stand-in federated provider and an MFA challenge stub all run through the same adapter without
  adapter changes, and the authorization server issues, introspects and revokes machine tokens on this
  Spring Boot / Java 25 combination.
- The spike produced the list of security stories Sprint 3 must deliver: hashing, enumeration and brute-force
  protection, token revocation (spike doc, section "Stories for Sprint 3").
- Spike code is **not** production code and is not part of the build. Sprint 3 writes the real implementation.
- Open for Sprint 3 design: access-token format for user sessions (self-contained JWT with a security-version
  check, or opaque reference tokens), authorization-record storage (database, with indexes by user for
  "sign out everywhere"), password-hash parameters tuned on production-like hardware.

## Alternatives considered

- **Hand-written token issuing:** rejected; security-critical protocol code better left to a maintained library.
- **An external identity product as a hard dependency:** rejected for V1; operational dependency and
  vendor coupling, and federation is covered by the abstraction later.
- **Using Spring Security's user-details mechanism directly everywhere:** works for V1 but couples every later
  provider to it; the adapter costs little and keeps federation additive.

## References

- Delivery plan decision D2 and Sprint S3; traceability note 2 (authentication vs authorization).
- Spike code: `platform/spikes/d2-auth-provider/`.
