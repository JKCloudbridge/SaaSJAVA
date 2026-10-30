# ADR-0213: Versions, rotation, the credential endpoints and the audit of use

- **Status:** Accepted
- **Date:** 2026-10-30
- **Sprint:** S27
- **Related:** [ADR-0054](0054-audit-v1-record-source-and-read-model.md), [ADR-0209](0209-integration-identity-permissions-and-tenant-isolation.md), [ADR-0210](0210-the-connection-lifecycle-and-connection-testing.md), [ADR-0212](0212-the-secrets-store-envelope-encryption-and-the-keys.md)

## Decision

### Tables (V084)

`secret_entry` (the name, the current version number), `secret_version` (numbered, sealed bytes, the key identifier, the first
use) and `secret_usage` (counts per clock hour). All three are tenant-scoped: `tenant_id`, forced row level security, the tenant
guard, a tenant-first partial unique index, entries in the isolation harness. The database holds the rules whatever the application
does:

- a stored version is **immutable**: the only update is the one-time mark of its first use, and (under `platform.secret_rewrap`)
  the change of the key identifier and wrapped key;
- a version is **deleted only with its secret or by pruning**, under `platform.secret_erase`, which only the store raises;
- the name of a secret never changes and its current version only moves forward;
- the shape of every column is checked (key id, sizes of the two ciphertexts, the algorithm).

### Versions and rotation

`put` writes the next version and makes it current. The newest `retained-versions` (default 3) are kept; older ones are pruned in
the same transaction. `resolve` returns the current version. A caller cannot pin a version: nothing needs one, and a pin would make
a rotation depend on every user. A resolve already under way when a rotation commits finishes with the value it read; the next
resolve on any instance sees the new one (`CredentialLifeIT`, two real instances).

**Rolling back** is `POST /credentials/{name}/rollback`: the version before the current one is opened and sealed again as a **new**
version, so the history only moves forward and nobody is shown a value. A second rollback returns to the value just left; the
words say "the version before".

### What a change of a credential does to the connections that use it

Chosen answer 4 of the sprint prompt: **a new version, a rollback or a removal voids the last test** of every connection that names
the credential, through `CredentialUsage.voidTests`, in the transaction of the change:

- a **tested** connection goes back to `UNTESTED`; a **switched-off** connection loses the test it would have been switched on by;
- a connection that is **on** keeps running and is not switched off: a rotation is the normal life of a credential and must not be
  an outage. Its last test shows as `VOIDED` with the reason `credential_changed`, and the next test or use shows whether the new
  value works (Sprint 29 turns a failing use into `FAILING`). The fingerprint of the definition does not change: the value was
  never part of it, and the lifecycle rules and the database guard of ADR-0210 stay as they are;
- **removing** a credential is refused while a connection that is switched on (or failing) uses it ("switch it off first");
  otherwise it is allowed and the connections that named it show `credential_missing` at their next test.

### The endpoints (`/api/v1/credentials`)

| Call | What | Ability |
|------|------|---------|
| `GET` | the list: name, version, dates, the hour of the latest use, how many connections use it, whether a version is kept to go back to | `credentials.view` |
| `PUT /{name}` | creates, or makes the next version; the value is in the body | `credentials.view` and `credentials.manage` |
| `POST /{name}/rollback` | goes back one version | both |
| `DELETE /{name}` | removes the secret and every version | both |

There is **no** call that returns a value, no "show", no "copy", and no response type has a field that could hold one (a
test of the contract scans for it). The value is in a request body only, never in an address, is never echoed, and a request type
that holds it prints as redacted. `credentials.view` and `credentials.manage` are separate from `integrations.*`: deciding where
the platform connects is not deciding which secret it uses. Licence, permission and entitlement stay three mechanisms; the plan
feature is `integrations`. Membership comes first (a platform person who is not a member finds nothing, and support access gives
no door). Words of refusal never repeat what was typed.

### Audit of use (answer 5)

Every event carries the entry identifier, the version and the reason key, **never** the value, a ciphertext, a key or the name:

| Type | When |
|------|------|
| `credentials.created`, `.rotated`, `.rolled_back`, `.deleted` | each change, with the acting person, in the transaction of the change |
| `credentials.first_used` | the first resolve of each version (one record per version) |
| `credentials.use_summary` | when a new clock hour opens, one record for each earlier hour of that secret: hour, uses, failures |
| `credentials.resolve_failed` | the first failed resolve of a secret in a clock hour, with the reason (`key_unavailable`, `unreadable`) |

Every use is **counted** (`secret_usage`, one upserted row per secret and hour); only the summary and the first of each kind reach
the trail, so a busy secret cannot flood it. The audience is the organization (`AuditAudience`, reasons readable). A metric
`platform.secrets.operations{operation,result}` counts calls by outcome; it carries no name.

## Consequences

- The counter row is hot for a secret used many times in an hour; each resolve holds it for one short transaction. Sprint 28 or 29
  caches a resolved value for seconds if a measurement says so.
- A removal that races a connection being switched on is decided by the lock order the connection's own guard already gives; the
  worst result is a test that says `credential_missing`.
- `VOIDED` joins `PASSED` and `FAILED` as a stored test result (V084 widens one check constraint of `integration_connection`).
