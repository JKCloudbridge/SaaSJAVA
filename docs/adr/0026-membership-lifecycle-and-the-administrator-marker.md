# ADR-0026: Membership lifecycle, the administrator marker and the check on every request

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S5
- **Related:** [ADR-0014](0014-tenancy-model-and-tenant-context.md), [ADR-0015](0015-row-level-security-implementation.md),
  [ADR-0021](0021-brute-force-protection-and-rate-limits.md), [ADR-0025](0025-founding-an-organization-and-the-minimal-membership.md),
  [ADR-0027](0027-which-organizations-does-a-person-belong-to.md), [ADR-0028](0028-invitations.md)

## Context

Sprint 4 wrote one kind of membership: an active founding administrator. Until now any signed-in person could sign in on any
organization's host and got no permission from it, because nothing tenant-private existed (Sprint 3 stop-gap). Sprint 5 makes the
membership real: people can leave and come back, someone must be allowed to invite and deactivate before access policies exist
(Sprint 7), the host alone must never decide who is inside, and an organization must not be left without anybody who can run it.

## Decision

1. **Two statuses, one table.** A membership is `ACTIVE` or `DEACTIVATED`; it starts `ACTIVE`. The legal moves are
   `ACTIVE <-> DEACTIVATED`; the database refuses any other (trigger `platform_membership_guard`, migration V013), as it does for
   tenants and users. `INVITED` is **not** a membership status: an invited person has no membership. The invitation is its own
   record ([ADR-0028](0028-invitations.md)), and a membership exists only from the moment the invitation is accepted. That keeps
   "grants nothing until accepted" literally true, lets the invitation request be identical for every address, and means an
   invited person never uses a licence (Sprint 6 rule: a licence is used when the membership becomes active).
2. **Two markers with different meanings.** `founding_administrator` stays a historical fact ("this person created the
   organization"): set once, never changed, grants nothing. `administrator` is the working marker: changeable, audited, and the
   only thing the administrative actions ask. Every action asks one small question through one class (`Administration`): *may this
   member administer the organization?* **Sprint 7 replaces the marker by an access policy** that carries the administrative
   abilities, and only `Administration` changes; the "last administrator" rule then moves onto that policy.
   The founder gets the marker at creation (V013 gives it to the founders Sprint 4 wrote); any administrator may grant or release it
   for another active member; a person may hold it without being the founder. A platform-provisioned organization (Sprint 6) has
   no founder: the first administrator who accepts a provisioning invitation gets both markers.
3. **Leaving the active state ends the marker.** Deactivating clears `administrator` (a returning member is named again on
   purpose); the database sets it. A deactivated membership can be reactivated by an administrator; it is not an administrator.
4. **The last administrator stays.** The last active holder of the marker cannot be deactivated, released or deleted. The
   service says so in plain words (409 `CONFLICT`); the **database enforces it as well** with a per-organization advisory lock
   taken inside the trigger, so two administrators stepping down at the same moment cannot both pass (tests with 2 to 5 rounds of
   real concurrency). An organization that lost all its administrators any other way (for example the account of the only
   administrator was closed) is repaired by Sprint 6's platform administrator, who can invite a new first administrator; until
   then the organization has nobody who can invite.
5. **Deactivation ends the person's sessions in that organization at once.** Login sessions and authorization grants bound to
   that host are revoked in the same transaction (`SessionRevocation.revokeAllIn`); the person's other organizations and the
   platform host are untouched. Reactivation brings nothing back: the old tokens stay revoked and the person signs in again.
6. **The check at sign-in.** On an organization host, after the password check, the person must be an **active member** of the
   organization the host names. If not, the answer is the same 401 and the same body as a wrong password (same status, headers
   and message; no session, no cookie), the password work has already been done, and the true reason (`not_a_member`) is only in
   the audit record. A sign-in on the platform host is unchanged.
7. **The check on every request.** A token is bound to the host it was issued on (Sprint 3). Since Sprint 5 the token check also
   requires an active membership in the organization the host names (`MembershipGate`, called from the token introspector): a
   token whose holder is not an active member is refused as an invalid token. This does not depend on the revocation of point 5
   (defence in depth). The tenant comes from the host as before; the membership is found with row level security under that
   tenant; `TenantContext` now carries `userId` **and** `membershipId`.
8. **Not decided here:** letting a member leave by themselves (flagged as deferred), transferring an organization, any
   permission beyond the marker (Sprint 7).

## Consequences

- Existing tests that signed people in on organization hosts without a membership had to add one (`TestMembers`).
- Each request on an organization host costs one more small query (the membership), after the token query of Sprint 3. Sprint 14's
  benchmark measures it together with the others.
- A closed or suspended user account still ends all sessions through the security version (Sprint 3); the membership is
  independent of the account's own status.
- The marker is a stop-gap by design; documented in the delivery plan at Sprint 7.

## Alternatives considered

- **Use `founding_administrator` as the authority.** Too rigid: a founder who leaves leaves the organization stuck, a second
  administrator is impossible, and a platform-provisioned organization has no founder.
- **An `INVITED` membership status.** Would need a user row for an address that has no account yet (so the invitation request
  would look at users and differ per address) or a nullable user.
- **Check the membership only at sign-in.** A token issued before the membership ended would keep working until it expired.

## Verification

`MembershipGuardIT` (database rules, concurrency), `MembershipIT` (sign-in refusal byte-for-byte equal to a wrong password,
deactivate/reactivate, last administrator, concurrency, allowed/denied/cross-tenant for every endpoint), `MigrationIT` (V013
against rows written by Sprint 4, run as a database owner that is not a superuser), `TenantAndUserContextIT`.
