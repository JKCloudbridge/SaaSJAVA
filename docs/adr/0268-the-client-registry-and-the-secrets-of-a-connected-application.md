# ADR-0268: The client registry and the secrets of a connected application (S31)

- **Status:** Accepted
- **Date:** 2026-11-23
- **Sprint:** S31
- **Related:** [ADR-0006](0006-no-static-api-keys.md), [ADR-0010](0010-base-schema-conventions.md), [ADR-0021](0021-brute-force-protection-and-rate-limits.md), [ADR-0212](0212-the-secrets-store-envelope-encryption-and-the-keys.md), [ADR-0267](0267-a-connected-application-acts-as-the-member-who-registers-it.md), [ADR-0269](0269-tokens-and-grants-of-connected-applications.md)

## Decision

1. **The registry is two tenant-scoped tables (migration V095, expand only).**
   - `oauth_client`: the public client identifier, a name, a description, the user and membership it acts as, its state
     (`ACTIVE` or `DISABLED`), its scopes, an optional list of objects, and the life of its tokens. Base columns, forced row
     level security, the tenant guard, a tenant-first unique index on the identifier. It never holds a secret.
   - `oauth_client_secret`: the secrets of an application, **as hashes only**, with when each was made, when it was last used,
     when it ends (an overlap) and its final end. Several rows for one application so that a rotation can overlap.
   - Only `ClientStore` names them (`ClientsArchitectureTest`). There is no system scope on either table: an application is
     found only on the host of the organization that registered it, because the lookup runs under the tenant of the host.
     The platform host, another organization's host and an unknown identifier all find nothing, and answer alike.
2. **The database holds the lifecycle whatever the application does.** The identifier, the organization and the member never
   change; a removed application stays removed (rows are marked, never deleted, and there is no delete policy); a secret row
   never changes except for three marks: when it was last used (only forward), when it stops (an overlap of at most 24
   hours, only shorter) and its end, which is final; nothing is ever deleted. Shape checks bound the scopes to the three of
   ADR-0270, the object names, the lifetime (300 to 3600 seconds) and the hash.
3. **Identifiers and secrets.** A client identifier is `ca_` and 24 letters and digits (about 143 bits): not guessable, not a
   secret. A secret is `cs_` and 43 URL-safe characters (256 bits from a secure random source); the start is not secret and
   lets a scanner of leaked text find one. The server makes both; the browser never does.
4. **Shown once.** The secret is in the answer of the one call that makes it (registering, or rotating) and in no other: the
   answer is never cached, no later call returns it, and it prints as redacted. The platform keeps only the hash, so a secret
   that was not copied is lost and the cure is a new secret.
5. **The hash is a fast one, SHA-256, and why.** The secret has full entropy, so there is nothing to guess and nothing for a
   slow hash to protect; and the token endpoint is open to anyone, so it must never be a cheap way to burn the server's
   processor time (a password hash is exactly that). The comparison is constant time (`MessageDigest.isEqual`), every
   working secret is compared whichever matches first, and an unknown application is compared against a fixed dummy so the
   time does not say whether it exists. There is no pepper (as ADR-0214 says for the keys of the secrets store).
6. **The client check is ours, not the library's.** `ClientAuthenticationProvider` replaces the library's check of a client
   secret at the token endpoint. Both `client_secret_basic` and `client_secret_post` are kept: the first is the one the
   protocol prefers, the second is what many simple programs can send, and the cost of keeping it is nil (one converter that
   the library already has). The web application's own public client is untouched and still handled by the library.
7. **Failed authentication is slowed and always looks the same.** Failed attempts are counted in the shared counters of
   sign-in (every instance sees the same numbers; an instance counts for itself when Redis is down): per source address
   (default 20 in 10 minutes) and per client identifier from any source (default 10 in 1 minute, short on purpose, so that
   whoever fills it keeps the owner out for one window only). The identifier is hashed in the key and the counter runs for an
   identifier that does not exist exactly as for one that does. It is brute-force protection, not a quota (Sprint 32).
   An unknown application, a wrong secret, a disabled application, an application on the wrong host, a member who can no
   longer act, a plan without the feature and the kill switch being off all answer **`invalid_client`, status 401, in the same
   words**. Only a caller who has used up the failed attempts is told to wait: **429 with `Retry-After`**. The true reason is
   audited (`clients.token_refused`) for an application that exists, at most once for each reason and window, so an attacker
   cannot fill the trail.
8. **Rotation.** Rotating makes a new secret and ends the older ones. By default **the old secret stops working at
   once** (the exit criterion). The administrator may choose, at that moment, an overlap of the older secrets of up to 24
   hours (audited; an older secret already ending sooner keeps its sooner end), and whether the tokens the older secrets
   already made also end now. Without that choice they live until they end, which is short (ADR-0269). An application may hold
   at most three working secrets at once (a setting). Ending one secret (`revoked`) and removing an application end the
   matching tokens in the same transaction.

## Consequences

- A copy of the table, a backup or a read-only SQL account yields hashes of random 256-bit secrets, which cannot be used.
- `SecretValue.reveal()` is not used here and the list of places that may call it is unchanged.
- A person who loses a secret cannot recover it; this is the point.
