# ADR-0045: System profiles, seeding, the backfill, and what licences mean for abilities

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S7
- **Related:** [ADR-0032](0032-licences-pools-assignments-and-their-rules.md), [ADR-0037](0037-provisioning-an-organization-for-a-client.md),
  [ADR-0039](0039-abilities-profiles-access-policies-and-individual-grants.md),
  [ADR-0044](0044-the-last-member-who-can-manage-access.md)

## Decision

1. **Two system profiles per organization**, created in the organization's own tenant context: the **Organization administrator**
   (`system_key = administrator`, every ability, licence type `admin`) and **Member** (`system_key = member`, licence type `user`,
   no abilities, the default). They are created when an organization is founded (first member), when a platform administrator
   provisions it, by the local seeder, by the backfill below, and lazily (idempotent, under the access lock) by the first member
   or the first read, so no path that makes a member can find them missing.
2. **Licences, one per use** (ADR-0032 generalised): `licence_assignment` gets a purpose, `PROFILE` (one per member, of the
   profile's type) or `ACCESS_POLICY` (one per member and licence-bound policy). Every row counts against the pool of its type,
   so the pool numbers and the guards of Sprint 6 are unchanged; existing rows are profile licences.
3. **Joining never fails for lack of a licence; abilities need it.** When a membership starts or returns the licence of the profile's
   type is tried and, when none is free, the member joins **unlicensed**: the profile is recorded and shown, the person can sign
   in, and the profile contributes no abilities until an administrator gives them a licence (`PUT /members/{id}/licence`) or
   frees one. The screens say so in words. Policies and grants are not affected (a policy that needs a licence cannot be assigned
   without one). An explicit assignment by an administrator is strict and refused with `409` when none is free. Sign-in is never
   blocked by licences.
4. **The first administrator always has authority.** A first administrator invited by a platform administrator, and an organization's
   founder, get the administrator profile and an administrator licence **even when the pool of `admin` has none free: the pool
   grows by one** (a platform administrator sees and changes pools in the console). Without this, a plan with no `admin` licences
   would create an organization nobody can administer. Other members never grow a pool.
5. **Deactivation and leaving release everything, and a return starts afresh.** In the same transaction every licence of the member
   goes back, their access policies, individual grants and role end (the licence of a licence-bound policy returns with it), and
   sessions of that organization end; only the recorded profile stays until they return. **Reactivation gives the organization's
   default profile** and tries a licence; it never fails for lack of one, and an administrator gives back more on purpose. This keeps
   the Sprint 5 rule that a returning member (an administrator included) is not an administrator again until named so.
6. **The backfill (V023)** runs once on every organization that exists when this sprint arrives (the developer's local ones; no
   organization is in production, so there are no clients to protect): it creates the two system profiles, **raises the admin pool
   to at least the number of current administrators** (an organization with more administrators than its plan allows keeps them;
   the plan's number is never lowered), swaps each administrator's user licence for an admin licence, and gives every active
   member a profile (administrator profile for a member who carried the Sprint 5 marker, the member profile for everyone else).
   Forced row level security is switched off for these statements and on again in the same transaction (the V013 and V018
   pattern), and the deferred guard of ADR-0044 is run before it is switched on. It is tested on a database migrated by a
   non-superuser owner and by running the application against the developer's database.
7. **A real organization with more administrators than admin licences** simply needs the platform administrator to raise the
   admin pool; until then the extra administrators are unlicensed and the screens show it.
