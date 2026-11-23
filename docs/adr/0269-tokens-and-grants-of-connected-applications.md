# ADR-0269: Tokens and grants of connected applications (S31)

- **Status:** Accepted
- **Date:** 2026-11-23
- **Sprint:** S31
- **Related:** [ADR-0019](0019-authentication-implementation.md), [ADR-0022](0022-platform-level-identity-tables-and-audit-v0.md), [ADR-0026](0026-membership-lifecycle-and-the-administrator-marker.md), [ADR-0267](0267-a-connected-application-acts-as-the-member-who-registers-it.md), [ADR-0268](0268-the-client-registry-and-the-secrets-of-a-connected-application.md)

## Decision

1. **Client credentials only, no refresh token.** An application asks again when its token ends. Tokens are opaque reference
   tokens, like the others: stored only as SHA-256 hashes in `oauth2_authorization` and checked against the database on
   every request. The library's client-credentials flow makes the token; two small wrappers are ours: a request that names no
   scope gets every scope the application holds (ADR-0270), and a scope it does not hold is the library's own `invalid_scope`.
2. **Lifetime: 15 minutes by default, 5 to 60 minutes per application**, and a ceiling of the platform in settings
   (`platform.identity.clients.tokens.*`); the database holds the same hard range (300 to 3600 seconds). The life is read from
   the registration when the token is made.
3. **One table is enough.** A machine grant is a row of `oauth2_authorization` like any other. The kind is the grant type
   `client_credentials`, and two new nullable columns (V095) name the application (`oauth_client_id`) and the secret that made
   the grant (`client_secret_id`); a check ties them together (a grant of this type always names both, no other grant does).
   The row's user is the member the registration names, read inside the transaction of the save; its principal is the public
   client identifier. A second authorization table would split revocation, cleaning and the lookups for no gain.
4. **The registration is read at every request.** A token only points at a grant. On each request `MachineTokens` reads the
   registration as it is now: the switch (`platform.identity.clients.enabled`), the plan feature `integrations`, the state of
   the application, the scopes it still holds and its object list. The scopes of the request are those of the token that the
   registration still holds; both can only narrow. A narrowing, a switch-off or a removal is therefore seen by the very next
   request on every instance whatever the token says, and the matching tokens are also revoked in the same transaction as
   the decision.
5. **Which events end a machine grant, and the one place each rule lives.**

   | Event | Ends it? | Where the rule lives |
   |---|---|---|
   | Member suspended, deactivated, or the membership ends | Yes, at once | the token check (`ALIVE` in `AuthorizationStore`: the user must be active; `TokenAuthentication.Introspector`: an active membership); ending a membership also revokes the grants bound to the organization (`revokeAllIn`) |
   | Application switched off, removed, or narrowed (scopes, objects, shorter life) | Yes | `ClientRegistry` calls `AuthorizationStore.revokeByClient` in the same transaction |
   | A secret ended or revoked | Its tokens, when revoked or when "end tokens now" is chosen | `ClientRegistry` calls `revokeBySecret` |
   | Rotation | Not the tokens, unless "end tokens now" is chosen | `ClientRegistry.rotate` |
   | Password change, "sign out everywhere", platform sign-out of a person, organization "sign everyone out" | **No** | `AuthorizationStore.revokeAll`, `revokeAllOfOrganization` and `liveGrantsOf` exclude grants that name an application; `ALIVE` ignores the security version for them |
   | Feature `integrations` lost, or the kill switch | Yes, at the next request | `MachineTokens` |

   An integration that a person set up must not die because they changed a password; they end it by switching the
   application off. A machine grant is **left out of the session lists** of Sprint 6 (a person's tokens are sessions, an
   application's are not), and ended grants are cleaned by the existing purge.
6. **The platform host and other hosts.** A grant is bound to the organization host it was issued on; presented anywhere
   else it is refused like any other token. A client is not even found on another host (row level security), so a grant of an
   application is not found there either.

## Consequences

- No new store, no cache of the registration: the cost is one indexed read per request of a machine token, next to the
  membership and permission reads that every request already pays.
- The member's security version still ends a person's sign-ins and not an application's; that asymmetry is deliberate and
  tested (`ClientMemberLeavesIT`).
