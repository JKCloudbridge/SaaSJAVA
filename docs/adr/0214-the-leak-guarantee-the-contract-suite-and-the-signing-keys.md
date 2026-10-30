# ADR-0214: The leak guarantee, the contract suite and the signing keys

- **Status:** Accepted
- **Date:** 2026-10-30
- **Sprint:** S27
- **Related:** [ADR-0007](0007-secrets-store-interface.md), [ADR-0012](0012-observability.md), [ADR-0019](0019-authentication-implementation.md), [ADR-0020](0020-passwords-and-hashing.md), [ADR-0212](0212-the-secrets-store-envelope-encryption-and-the-keys.md), [ADR-0213](0213-versions-rotation-the-credential-endpoints-and-the-audit-of-use.md)

## The statement of what a secret may touch

A secret value may exist in **three** places and no other:

1. the **request body** that carries it in, until it is sealed (an immutable `String` while the request is parsed, a wiped array
   from then on);
2. the **store** (`DatabaseSecretsStore` and the `EnvelopeCipher` it uses), in a wiped array, for the length of one call;
3. the **header built at the moment of use** by the one caller that sends it (`ConnectionTesting`), destroyed in a `finally`.

Everything else is refused structurally where a rule can say so:

| Rule | Held by |
|------|---------|
| `SecretValue` has no `equals`, `hashCode`, accessor or serialisation, and prints `SecretValue[REDACTED]`; Jackson writes none of it | `SecretsArchitectureTest.aSecretValueCannotBeTurnedIntoJson`, and the value tests of the shared kernel (`SecretsContractTest`) |
| only `DatabaseSecretsStore`, `ConnectionTesting` and the shared-kernel type itself call `reveal()` | `SecretsArchitectureTest` (ArchUnit) |
| only `SecretTables` names the three tables; only the store depends on the implementation package | `SecretsArchitectureTest` (source scan and ArchUnit) |
| a request type that holds a value, a key holder, a sealed pair, a stored version and the settings print as redacted | `WriteCredentialRequest`, `KeyEncryptionKeys`, `EnvelopeCipher.Sealed`, `SecretTables.StoredVersion`, `SecretsProperties.Kek` |
| a failure of the store carries a stable reason and no message, cause or stack trace | `SecretStoreException`, `EnvelopeCipher.SealException` |
| no view of a credential has a field that could hold a value; the only `GET` of the endpoints is the list | `SecretsArchitectureTest`, `noValueInTheBrowser.test.ts` |
| the database holds no plaintext and cannot be made to change a ciphertext | `SecretStoreGuardIT` (as the application role and as the owner) |
| the audit trail, the logs, the metrics and the error tracker see identifiers, versions, reasons and counts only | `SecretsLogsAreCleanIT`, `ErrorTracker` receives `SecretFailure`, which names a class only |

### The secret-leak test

`SecretsLogsAreCleanIT` creates a canary credential, uses it in a real connection test against a service the test starts, replaces
it, rolls it back, sends refused writes and removes it, with the application, the security framework, the web layer and the JDBC
layer logging at debug level. It then searches for **the canary and for the ciphertext that holds it** in the log file, the audit
trail, every answer the API gave, the metrics endpoint, the committed API description and **every table of the database** (the
organization's tables as the application role, the others as the owner). The ciphertext is allowed in one table, its own; the
canary in none. A positive control (`theSearchCanFindWhatItLooksFor`) proves the search finds ciphertext where it is. There is no
export in the platform that could carry a credential; when one is added it joins this test.

## The reusable contract suite

ADR-0007 promised the contract as a test suite every implementation must pass. `SecretsStoreContract` (an abstract class in the
tests) holds it: resolve what was put, versions, `describe` facts only, isolation between organizations, deletion that is complete
and repeatable, a value that is a copy, any text round-trips, nothing prints a value, and the platform's scope is held apart or
refused. It is run by `TestSecretsStoreContractTest` (the in-memory store of the tests, which stays as the fast alternative for tests
that need a store without a database) and by `DatabaseSecretsStoreContractIT` (the real one). A future implementation (an adapter
for a deployment target) extends it and passes or is not accepted. `TestSecrets` is `@Primary` when imported, so a test that imports
it uses it although the real store is always present.

## The signing keys and the password pepper

The Sprint 3 key provider (`SigningKeys`, ADR-0019) **stays with the deployment's files**. Moving the signing keys into the store
would put them in the database, but the application needs them at start-up to build the token encoder, before a request, a tenant
context or (on a first start) the migrated schema exist, and the keys must not depend on a table whose own protection depends on a
second key. The two pieces of key material have the same source (the deployment's secret store) and the same rule (never the
repository, a table, a log), and the current path keeps working unchanged. The store can be offered to a later key provider through
the platform's scope when something needs it (not built: ADR-0215).

There is **no pepper**: ADR-0020 lists it as an alternative that was not adopted and the code has none. The mail queue's addresses
(ADR-0024) are not credentials and stay as they are.

## Consequences

- A new place that must handle a secret value is an architecture-test change and an ADR, not a convenience.
- A test that wants a canary in a new surface (an export, a new log) adds it to `SecretsLogsAreCleanIT`.
