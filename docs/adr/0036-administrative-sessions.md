# ADR-0036: Administrative sessions: listing, signing a person out everywhere, signing an organization out

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S6
- **Related:** [ADR-0019](0019-authentication-implementation.md), [ADR-0021](0021-brute-force-protection-and-rate-limits.md),
  [ADR-0026](0026-membership-lifecycle-and-the-administrator-marker.md), [ADR-0030](0030-platform-roles-and-the-first-platform-administrator.md)

## Context

Sprint 3 stored sign-ins and token grants with an index by user and left the listing and the "sign this person out everywhere"
for the platform administrators (S3-SEC-17); Sprint 5 left an organization-wide "sign everyone out" open. Both are incident tools:
a stolen account, a leaving administrator, a suspected misuse.

## Decision

1. **`SessionAdministration`** (public contract of the identity module) has three operations; the caller decides who may ask.
   - **List what a person holds:** a platform administrator or support person gives an address and a reason; the answer lists
     each live sign-in as *kind* (`SIGN_IN` step, `TOKENS` signed in), *organization* (short name, or none for the platform
     host), *when it began* and *when it ends at the latest*. **Never a token, a hash or a secret.** An unknown address and a
     person with nothing give the same empty answer (byte for byte, tested), so the call does not tell whether an address has an
     account.
   - **Sign a person out everywhere:** ends every sign-in step and every grant on every host; the person can sign in again (it is
     not a suspension); answers `204` for an unknown address too.
   - **Sign an organization out:** ends every sign-in step and grant bound to that organization's host. A **platform
     administrator or support** person may do it for any organization (with a reason); an **organization's own administrators**
     may do it for theirs (`POST /api/v1/organization/sign-out-all`), and they stay signed in themselves.
2. **The address travels in the body**, never in a URL or an access log (`POST .../sessions/lookup`, `.../sessions/sign-out`).
3. **Every use is audited**, with the platform person or administrator, the target organization when there is one, and the
   address only as a hash (`platform.sessions.listed`, `platform.sessions.signed_out_everywhere`, `auth.organization.signed_out_all`).
   A test proves that the address never reaches the audit table.
4. Suspending or closing an organization also ends its sessions (ADR-0038), using the same operation.

## Trade-off

A platform person who looks at an address learns whether that person is **signed in somewhere** (a non-empty list). That is the
tool's purpose and is limited to two roles and audited; it does not tell whether an account exists when nothing is live.

## Verification

`SessionAdministrationIT` (listing without secrets, the same answer for an unknown address, sign-out everywhere, the audit holds
no address, validation), `OrganizationLifecycleIT` (an organization's administrator signs everyone else out; a platform
administrator signs everyone out), `PlatformRolesIT` (who may call).
