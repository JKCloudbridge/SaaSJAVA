# ADR-0006: No static API keys in V1

- **Status:** Accepted
- **Date:** 2026-10-01
- **Decision IDs:** D10
- **Sprint:** S0

## Context

Machine access to the public API needs credentials. Static, long-lived API keys are simple but are bearer
secrets that never expire by default, are frequently committed or logged, are hard to scope per request and
are hard to rotate without breaking callers.

## Decision

1. **Version 1 has no static long-lived API keys.**
2. Machine and service access uses **OAuth 2.0 client credentials** with **short-lived access tokens**
   issued by the authorization server (ADR-0005). Client secrets are managed credentials: created, rotated and
   revoked through the platform, stored only as hashes, and shown once at creation.
3. Scopes are necessary but never sufficient: every request is still authorized per the granting user's or
   client's actual permissions in the active tenant.
4. A rotated or revoked client secret must stop working immediately (Sprint 31 exit criterion).
5. The decision is revisited only if a **concrete simple-integration need** appears that client credentials
   cannot serve; it would then be a new ADR.

## Consequences

- Slightly more work for the simplest integrations (a token request before API calls).
- No class of "forgotten key in a repository that works forever".
- Sprint 31 owns the client lifecycle; the spike confirmed issuing, introspection and revocation of
  client-credentials tokens works on the chosen stack.

## Alternatives considered

- **API keys with hashing, scopes and expiry:** viable, but doubles the credential model; rejected for V1.
- **Mutual TLS only:** strong but heavy for most callers.

## References

- Delivery plan decision D10, Sprints S30 to S31; traceability note 8.
- Spike evidence: `spikes/d2-auth-provider/src/test/java/spike/auth/AuthorizationServerOnJava25Test.java`.
