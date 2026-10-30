# ADR-0212: The secrets store: envelope encryption in the platform database, and the keys

- **Status:** Accepted
- **Date:** 2026-10-30
- **Sprint:** S27
- **Decision IDs:** D4 (implementation), D5 (no deployment target yet)
- **Related:** [ADR-0003](0003-tenant-isolation-strategy.md), [ADR-0007](0007-secrets-store-interface.md), [ADR-0019](0019-authentication-implementation.md), [ADR-0208](0208-the-integration-definition-and-the-address-rules.md)

## Context

Sprint 26 stores only the **name** of a credential; with no store behind the D4 interface every connection that signs in ends its
test with `credential_store_unavailable`. This sprint delivers the first implementation. The deployment target is still undecided
(D5), so the implementation must need no external service, and ADR-0007 already names encrypted columns in the main database as an
acceptable implementation behind the interface (not as the interface).

## Decision

1. **Envelope encryption in the platform's own database.** A fresh 256-bit **data key** encrypts one version of one secret; a
   **key-encryption key** (KEK) supplied by the deployment wraps the data key. Both steps are AES-256 in Galois/Counter mode from the
   JDK (no dependency). Nonces are 96 random bits drawn for every operation. Plaintext never exists in the database: no SQL, backup
   or dump can read a value.
2. **The ciphertext is bound to its address.** The associated data of both steps is `sv1|<purpose>|tenant:<id>|<name>|<version>`
   (purpose `val` for the value, `key` for the wrapped key). A sealed value or a wrapped key copied to another secret, another
   version or another organization, or a wrapped key presented as a sealed value, does not open (`EnvelopeCipherTest`). Row level
   security is the second layer (ADR-0003); the address in the ciphertext is the first.
3. **Where the KEK comes from.** The deployment gives it as a mounted file or as an environment variable of its secret manager
   (`platform.secrets.kek.active-id` with `active-key` or `active-file`; a key is 32 random bytes in Base64). The repository, the
   tables, the logs and the metadata never hold it. Older keys are listed under `platform.secrets.kek.previous` and only open what
   they wrapped. `local` reads its key from `infra/local/.env` (`LOCAL_SECRETS_KEY`, made by `init-env.ps1`); the test profile has a
   fixed throw-away key so that two instances of one test open each other's secrets.
4. **A missing or invalid key stops the application from starting, in every profile.** Chosen over "start and fail every use":
   a platform that accepts a credential it cannot open later, or that quietly serves a deployment with half its connections
   broken, hides the fault until someone depends on it. The message says which setting is wrong and never repeats a value or a path.
5. **Re-wrapping under a new key is lazy.** A resolve that finds a version wrapped under a previous key wraps its data key again
   under the active key, in its own transaction, without touching the value (the database allows that change only under the
   setting `platform.secret_rewrap`, which only the store raises). A previous key stays configured until the owner's query in
   `docs/runbooks/secrets.md` shows no row wrapped by it; replacing a value also moves it.
6. **The implementation is a module of its own, `secrets`.** It depends on `sharedkernel`, `tenant`, `security` (the abilities),
   `licensing` (the plan feature) and `observability`. `integration` depends only on the shared-kernel contract, never on the
   implementation (an architecture test), and the module does not depend on `integration`: the one question it must ask the
   connections ("who uses this credential?") goes through the shared-kernel contract `CredentialUsage`, implemented by `integration`.
7. **The scope of the platform is not supported** by this store. Nothing needs a platform secret (the signing keys stay with the
   deployment, ADR-0214), and a table with no reader would be a placeholder. A reference to the platform's scope, or one that names no
   organization, is refused with the stable reason `unsupported_scope`; it is never ignored or treated as an organization.
8. **The store works for the organization its address names.** On a thread that has no tenant context (a connection test resolves on
   a worker thread) the store opens that organization's context itself, in its own short transaction. On a thread that already
   works for an organization, an address that names another one is refused (`scope_mismatch`).
9. **Failures are one type.** `SecretStoreException` carries a stable reason (`key_unavailable`, `unreadable`, `unsupported_scope`,
   `scope_mismatch`, `value_rejected`, `limit_reached`, `storage_failed`); its message is the reason key, it has no cause and no stack
   trace, so a log line or error report built from it cannot leak anything. The cipher's failure is one exception with no message.

## Consequences

- Rotating the KEK is an operation of the deployment (add the new key, keep the old one as previous, deploy, wait, remove): see the
  runbook. Losing a KEK that wrapped rows loses those secrets; the runbook says what to do (replace the values).
- A cost note: a resolve is one read of two rows, one decryption and one counted write (ADR-0213). Sprints 28 and 29 may keep a
  resolved value for seconds if the hot row of the counter shows up in a measurement.
- A value arrives as an immutable `String` while the request is parsed and is converted to a wiped array at once; heap dumps of a
  running instance are outside this ADR's guarantee (Sprint 34).
- The limits (`platform.secrets.limits.*`): 4096 characters a value, 200 secrets an organization, 3 versions kept.

## Alternatives considered

- **An external secrets manager adapter now:** rejected; there is no deployment target (D5) and an adapter built against a guess is
  worse than none. The D4 interface keeps the door open.
- **Encrypting with the database's own functions:** rejected; the key would reach the database server and its logs.
- **One key for every secret:** rejected; a data key per version limits what one leak or one nonce mistake can expose, and lets the
  KEK be replaced without re-encrypting values.
- **A library for the cipher:** rejected; the JDK's authenticated mode is enough and adds no dependency to audit.
