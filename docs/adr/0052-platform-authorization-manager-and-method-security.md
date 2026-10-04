# ADR-0052: PlatformAuthorizationManager and method security

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S8
- **Related:** [ADR-0030](0030-platform-roles-and-the-first-platform-administrator.md), [ADR-0035](0035-controlled-support-access.md); delivery plan S8

## Context

Every platform endpoint began with `PlatformCaller.require(principal, roles...)`: the host check, the signed-in person and the role
were repeated 26 times and a forgotten line would have left an endpoint open.

## Decision

1. **One rule.** `PlatformAuthorizationManager` (an `AuthorizationManager` of method security) runs before every method marked
   `@PlatformFunction({roles})`: platform host (else `NOT_FOUND`), a signed-in person (else `UNAUTHENTICATED`), one of the roles (else
   `FORBIDDEN`, audited by `PlatformRoles`). The answers, the audit and the uniform behaviour are exactly the ones the console had.
2. **No endpoint without a rule.** Architecture tests fail the build when a controller method of the platform console lacks
   `@PlatformFunction`, and when anything but the manager calls `PlatformRoles.require`. The organization side of support access
   (decided by an organization's own ability) moved to its own controller so the rule can be strict.
3. **Wiring.** `@EnableMethodSecurity` with a custom advisor on the annotation; controllers are proxied by class; the advisor looks the
   manager up lazily so creating it early does not leave the beans it uses unproxied.
4. **What it does not do.** Tenant-configurable decisions (what a member of an organization may do) stay with `Administration`,
   `AccessGate` and the engine, which must run inside the transaction that does the work; platform roles give no organization authority.
5. **Proof nothing became more open:** `PlatformRolesIT`, `EndpointExposureIT`, the architecture rules and `PlatformAuthorizationManagerTest`.
