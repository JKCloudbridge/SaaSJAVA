# ADR-0007: Cloud-neutral secrets store interface

- **Status:** Accepted (interface only; implementation in Sprint 27)
- **Date:** 2026-10-01
- **Decision IDs:** D4
- **Sprint:** S0

## Context

Integrations need credentials. They must never appear in code, configuration files, metadata tables, logs,
API responses or exports. The deployment target is not decided yet (decision D5), so the platform must not
couple to one secrets product.

## Decision

1. Define `SecretsStore` in the shared kernel with four operations: `resolve`, `put`, `describe`, `delete`.
2. Addresses are `SecretRef(scope, name)`, where scope is `platform` or `tenant:<tenant-id>`. A lookup can
   never cross scopes.
3. `SecretValue` is a dedicated type whose `toString()` is always redacted, whose plaintext is only available
   through an explicit `reveal()` call at the moment of use, and which can be wiped.
4. `put` always creates a new version (rotation is writing a new version). `describe` returns only
   non-sensitive metadata (version, timestamps) and is the only view allowed in UIs, APIs and audit records.
5. Implementations must audit every access without recording values, fail without leaking secret material,
   and enforce tenant isolation. The concrete store is chosen per deployment target in Sprint 27.

## Consequences

- Integration code can be written against the interface long before a store exists.
- Contract rules are documented on the interface; Sprint 27 turns them into a reusable test suite that every
  implementation must pass.
- In Sprint 0 only value-type behaviour is tested (`SecretsContractTest`); there is no implementation to test.

## Alternatives considered

- **Environment variables or config files for integration credentials:** rejected; not per-tenant, not rotatable
  at runtime, easy to leak.
- **Encrypted columns in the main database:** acceptable as one future implementation behind the interface,
  not as the interface.

## References

- Delivery plan decision D4, Sprints S0 and S27.
- Code: `platform-shared-kernel/src/main/java/app/platform/sharedkernel/secrets/`.
