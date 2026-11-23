# ADR-0273: Abilities, entitlement, the key space of keyed writes, and what Sprint 31 leaves out (S31)

- **Status:** Accepted
- **Date:** 2026-11-23
- **Sprint:** S31
- **Related:** [ADR-0006](0006-no-static-api-keys.md), [ADR-0039](0039-abilities-profiles-access-policies-and-individual-grants.md), [ADR-0213](0213-versions-rotation-the-credential-endpoints-and-the-audit-of-use.md), [ADR-0246](0246-keyed-writes-the-idempotency-key-and-its-memory.md), [ADR-0267](0267-a-connected-application-acts-as-the-member-who-registers-it.md)

## Decision

1. **Two new abilities, apart from `integrations.*` and `credentials.*`.** `clients.view` lists and reads (never a secret) and
   draws the catalogue; `clients.manage` registers an application (which acts as the caller), changes it within the rules,
   switches it off and on, makes and ends secrets, and removes **any application of the organization**, but can never change
   whom it acts as. A change needs both abilities (as credentials do). A platform role has neither: support access gives no
   door here, as for credentials.
2. **Three separate mechanisms, asked in this order and never merged:** the caller is an active member of the organization of
   the host, then the ability, then the plan feature. The plan feature is the existing **`integrations`**: with it off the
   management endpoints refuse (`CONFLICT`, in words), the token endpoint refuses like a wrong secret, and live tokens stop
   at the next request. The user-token door of Sprint 30 does not depend on it and stays as it was.
3. **Kill switch.** `platform.identity.clients.enabled` (`PLATFORM_CLIENTS_ENABLED`, default true): false makes the token
   endpoint refuse client credentials like a wrong secret, the management endpoints answer 404 and live tokens stop.
4. **The key space of keyed writes.** `Idempotency-Key` is scoped to (organization, member, key). Two applications acting as
   one member would have shared a key space, so a keyed write is claimed under a fixed name made of the member and, for an
   application, the application (`PublicCaller.idempotencyKeySpace`); a person's own token keeps the member alone, so nothing
   stored before changes meaning. No migration was needed: the column holds any identifier.
5. **The contract.** The OAuth flow is documented in the public contract and guide additively (the token address is relative
   to the host the document is served from; the host is never written); `bearerAuth` stays for a person's token.

## What Sprint 31 leaves out, on purpose

| Left out | Why |
|---|---|
| User-delegated applications: authorization code with a consent screen, consent records, "applications you have authorised", refresh for public clients | A second half with its own security review (the library gives a public client no refresh token; a reviewed hook is a decision of its own, ADR-0019). Owner's decision, 2026-11-23: a following sprint |
| Static long-lived API keys | ADR-0006 and decision D10: the secret is only ever exchanged for a short-lived token, and the API accepts no secret as a credential |
| Webhooks, quotas, rate-limit headers, a limit per client | Sprint 32. The client identity is one attribute on the principal and one value in the logging context, ready for it; no limit is built |
| A service identity | ADR-0267 (the dedicated integration member practice, and the triggers to revisit) |
| A developer portal outside Setup, mutual TLS, token introspection or exchange endpoints, a second authorization table | Not needed for V1; each is its own decision |
| Allowing a browser origin for an application | The public API stays for servers; no cross-origin rule (ADR-0249) |
| File or import access through the public API | Not the data interface (ADR-0244) |

## Consequences

- Sprint 32 can attach limits and webhooks to the client identity without changing the token model.
- The contract baseline test accepts the new flow as an addition; no existing path, status or property changed.
