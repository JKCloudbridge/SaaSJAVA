# ADR-0215: What Sprint 27 leaves out, and why

- **Status:** Accepted
- **Date:** 2026-10-30
- **Sprint:** S27
- **Related:** [ADR-0211](0211-what-sprint-26-leaves-out.md), [ADR-0212](0212-the-secrets-store-envelope-encryption-and-the-keys.md) to [ADR-0214](0214-the-leak-guarantee-the-contract-suite-and-the-signing-keys.md)

Written down so that nobody reads an omission as an oversight.

| Left out | Why | Where it comes |
|----------|-----|----------------|
| **An adapter for an external secrets manager** | the D4 interface allows one per deployment target; there is no target (D5), and an adapter written against a guess is worse than none | with the target, behind the same interface, passing `SecretsStoreContract` |
| **Automatic rotation schedules, expiry, reminders** | a rotation needs the external system's side too (a new key issued there); the platform only holds the value | on request; the audit and `lastUsedAt` give the facts |
| **Secrets of the platform's own scope, and a store for platform people** | nothing reads one; the signing keys stay with the deployment (ADR-0214); a table without a reader would be a placeholder | when a platform feature needs one |
| **Hardware-backed keys, a key service, envelope keys per organization** | the KEK is the deployment's to protect (runbook); a per-organization KEK needs a key service | Sprint 34 review, or with a deployment target |
| **A way to show or copy a value (a break-glass reveal)** | never; the only recovery for a lost value is to write a new one | never |
| **Pinning a version to a connection** | nothing needs it; it would make every rotation depend on every user | not planned |
| **OAuth client secrets of the public API, webhook signing secrets** | they have their own lifecycle (hashing, one-time display) and the machine-client work | Sprints 31 and 32 |
| **Mapping, sending and receiving records** | no credential is used for a record before Sprint 28 | Sprint 28 |
| **Turning a failing use into `FAILING` after a rotation** | no code uses a connection outside a test yet | Sprint 29 |
| **Caching a resolved value** | one counted write per resolve is cheap for tests; measure before adding a cache that holds plaintext in memory longer | Sprint 28 or 29, if a measurement asks |
| **Re-wrapping every secret in one step when the KEK is replaced** | a re-wrap needs the organization's context for each row; the lazy re-wrap on use, the runbook's owner query and replacing a value cover the operation | on request |
| **A screen for the audit of credentials** | the audit viewer already shows the `credentials.*` records to those who may view audit | not needed |
| **Import or export of credentials** | there is no export, which is part of the guarantee | never |

A need that fits one of these rows cannot be expressed today; it is never half-supported.
