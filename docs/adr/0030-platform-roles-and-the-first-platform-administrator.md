# ADR-0030: Platform roles, the first platform administrator and what protects those accounts

- **Status:** Accepted (multi-factor sign-in is a go-live gate, not built in Sprint 6)
- **Date:** 2026-10-02
- **Sprint:** S6
- **Related:** [ADR-0003](0003-rls-defence-in-depth.md), [ADR-0019](0019-authentication-implementation.md),
  [ADR-0021](0021-brute-force-protection-and-rate-limits.md), [ADR-0026](0026-membership-lifecycle-and-the-administrator-marker.md),
  [ADR-0031](0031-where-the-platform-tables-live-and-how-an-organization-is-reached.md),
  [../production-transition-plan.md](../production-transition-plan.md)

## Context

Until Sprint 6 nobody could operate the platform itself: a product owner could do only what any person can. The platform
needs people who create organizations for clients, suspend them, manage plans and help with support, without any of that
giving them a way into an organization's members or business data. The most powerful accounts of the platform also need a
documented way to come into existence (never a default account) and extra protection until multi-factor sign-in exists.

## Decision

1. **Three platform roles, stored in a platform-level table** (`platform_role_assignment`, V014): a person (an ordinary
   account) holds a role; it is not a property of an organization. `PLATFORM_ADMIN` does everything on the console
   (organizations and their lifecycle, provisioning, plans, pools, entitlements, platform roles, sessions);
   `PLATFORM_SUPPORT` reads the state of an organization, asks for support access, resends a first-administrator invitation
   and signs a person out; `PLATFORM_BILLING` manages plans and subscriptions and reads the rest. **No role reads a member
   list or business data of an organization**; the API simply has no such endpoint for the platform.
2. **A platform role is separate from organization authority.** A platform administrator who is also a member of an
   organization is an ordinary member there (`Administration` asks the membership only); a platform person who is not a member
   cannot sign in on that organization's host (refused like a wrong password). A test walks every platform endpoint with
   every role and with an organization administrator and an ordinary person (`PlatformRolesIT`).
3. **One small class asks "which roles?"**: `PlatformRoles` (public contract of the identity module). It reads the database on
   every call (nothing is cached), so a revoked role stops working at the next request. Every platform endpoint first requires
   the **platform host** (an organization host answers `NOT_FOUND`: the console does not exist there), then a signed-in
   person, then one of the roles the action needs; a refusal is audited (`platform.action.refused`).
4. **Granting and revoking** are done by a platform administrator, by the address of an existing active account, and are
   audited (`platform.role.granted`, `platform.role.revoked`). Granting by address tells a platform administrator whether an
   address has an account; that is accepted for the platform's most trusted operators (the alternative, a role for an account
   that does not exist, is worse).
5. **The last platform administrator stays**, in the database and in the service: the trigger on `platform_role_assignment`
   refuses to end the last live `PLATFORM_ADMIN` assignment of an active account and takes an advisory lock, so two
   administrators stepping down at the same moment leave one (tested with real concurrency).
6. **The first one is created by a documented manual step, never by a default account:** manual migration `M002`
   (`db/manual/M002__grant_first_platform_administrator.sql`), run by the database owner with the e-mail address of an
   **existing active account**. It refuses an unknown or closed account, refuses when a platform administrator already exists
   (unless told `allow_additional=yes`, for repair), is safe to run twice and writes an audit record
   (`platform.role.granted`, `how=manual_bootstrap`). Later administrators are granted from the console. The local profile
   seeds `platform-a`, `support-a` and `billing-a` (profile `local` only, password from `LOCAL_SEED_PASSWORD`).
7. **What protects these accounts until multi-factor sign-in exists:**
   - the console and every platform endpoint answer only on the platform host;
   - a **short session**: a sign-in of a person who holds a platform role stops working on the platform host 4 hours after it
     began (`platform.identity.tokens.platform-session-max`), however often it was refreshed;
   - a **lock that comes sooner**: 3 consecutive failed sign-ins lock such an account (5 for anybody else), with the same
     steps and the same cap, and the owner is told by mail;
   - a **loud audit record** for every platform sign-in (`platform.sign_in.succeeded`, with the roles and the source);
   - every platform action is audited with the platform person, the target organization and a bounded reason.
8. **Gate: multi-factor sign-in for platform roles is delivered before launch** (Sprint 34 at the latest). It is recorded in
   the go-live gate of the transition plan. Until then the real environment must have at most two platform administrators,
   each with an account of their own and a long password of their own.

## Trade-offs (plain words)

- A person's role is read on every platform request and on every request of a platform person on the platform host (for the
  short session). It is one small query; the cache decision belongs to Sprint 9.
- The short session means a platform administrator signs in again after four hours of work. That is the price of the extra
  protection until the second factor exists.
- Locking after three failures lets someone lock a platform account by guessing (a denial of service). The lock is short,
  capped and ends by itself, the owner is told, and a password reset is never blocked by it (ADR-0021).

## Verification

`PlatformRolesIT` (role against every endpoint, no ability without a role, host rules, forged tenant header, grant and
revoke audited and effective at once, the last administrator under concurrency, loud sign-in record, short session, quicker
lock), `FirstPlatformAdministratorScriptIT` (M002 run with the real command line tool), the database trigger tests inside
`PlatformRolesIT`, `SchemaConventionsIT`.
