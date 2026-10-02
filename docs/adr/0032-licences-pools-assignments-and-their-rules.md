# ADR-0032: Licences: types as data, a pool per organization, an assignment per member, and the rules

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S6
- **Related:** [ADR-0026](0026-membership-lifecycle-and-the-administrator-marker.md), [ADR-0028](0028-invitations.md),
  [ADR-0031](0031-where-the-platform-tables-live-and-how-an-organization-is-reached.md),
  [ADR-0034](0034-feature-entitlements.md); delivery plan S6 and S7

## Context

The product owner sells numbers of licences. An organization's administrators then decide which of their members hold one.
Sprint 7 will make a profile belong to a user-licence type and may bind an access policy to a licence type, so the model has to
be generic: a licence only **counts and limits assignments**; what a person may do is decided by permissions and which features
exist by entitlements (three separate mechanisms, architecture note 2, section 32).

## Decision

1. **The model.** A *licence type* is data (`licence_type`; the first two are `user` and `admin`). A *plan* has default
   quantities per type (`plan_licence`). An organization has one *pool* per licence type (`licence_pool`: quantity; the numbers
   "assigned" and "available" are counted, never stored twice) and one *assignment* per member who holds a licence
   (`licence_assignment`; a member holds at most one; a release is a soft delete, so the history stays).
2. **Who assigns.** The administrators of the organization, through `Administration` (so Sprint 7 changes one class), by
   `PUT /api/v1/members/{id}/licence` and `DELETE` of the same; the numbers are `GET /api/v1/licences`. Giving a member another
   type moves them. A platform administrator sets the **size** of a pool (`PUT .../pools/{type}`), never an assignment.
3. **One winner for the last licence.** The pool row is locked (`select ... for update`) before the count is read, and a trigger
   on the assignment does the same inside the database, so two administrators asking for the last free licence are decided one
   after the other: one succeeds, the other gets `409 CONFLICT` "No licence of this type is free." (tested: four candidates, two
   administrators, three rounds).
4. **A pool cannot be reduced below what is in use**, by the service (`409`) and by a trigger on the pool, whoever writes
   (tested as the application role). A plan change that would leave any pool below use is refused as a whole.
5. **Deactivating a member gives the licence back, in the same transaction** (`MemberService` calls `Licences.release`; this is
   why the module graph was changed so that `identity` depends on `licensing` and not the other way round).
6. **An invited person uses no licence.** A membership exists only after acceptance (ADR-0028), so nothing is used before.
7. **When a membership becomes active, the default licence is assigned if one is free** (acceptance of an invitation, founding
   an organization, reactivation): the default type is `user` (`platform.licensing.default-licence-type`). **It never fails
   the action for lack of one**: acceptance and reactivation always succeed and the person simply shows as unlicensed; refusing
   someone who already did the work would also tell them the state of the pool.
8. **"Unlicensed" in this sprint** is recorded and shown (the members list, the numbers); sign-in and every action are **not**
   blocked by it. Sprint 7 starts to require a licence of the profile's type to assign a profile.
9. **Organizations that existed before this sprint** (V018): each non-closed one gets a 30-day trial on the plan `trial`; its
   `user` pool is the larger of the plan's quantity and its active members, and every active member holds a user licence, so
   nothing that worked yesterday is unlicensed today (tested on a database migrated by a non-superuser owner).

## Trade-offs

- The count is computed from the assignments on each read (no stored counter): one small indexed count, no way to drift.
- An administrator can see how many licences are free; an invitation acceptance deliberately does not reveal it.

## Verification

`LicencesIT` (numbers, assign, release, move, no free licence, no pool, member refused, foreign member and forged header,
platform host, concurrency, below-use by service and database, deactivation, reactivation with and without a free licence,
a deactivated member refused, licence vs entitlement vs marker), `SubscriptionsAndEntitlementsIT` (plan change resizes,
refused as a whole), `ProvisioningIT` (the first administrator holds the default licence), `MigrationIT` (backfill),
`TenantIsolationIT` (the leak harness).
