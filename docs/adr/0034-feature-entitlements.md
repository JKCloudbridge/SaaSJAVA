# ADR-0034: Feature entitlements: keys as data, plan defaults, per-organization overrides, one read contract

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S6
- **Related:** [ADR-0031](0031-where-the-platform-tables-live-and-how-an-organization-is-reached.md),
  [ADR-0032](0032-licences-pools-assignments-and-their-rules.md), [ADR-0033](0033-subscriptions-trials-and-the-organization-limit.md)

## Context

Which features an organization bought (for example approvals) is a third mechanism, independent of who holds a licence and what a
person may do. Other modules will ask "is this feature on for the organization of this request?" many times; the answer must be
correct at once after a platform administrator changes it.

## Decision

1. **Feature keys are data** (`feature`: key and name; the first four are `approvals`, `workflows`, `integrations`,
   `public-api`). A plan includes a set of them (`plan_feature`).
2. **The effective answer** is: the explicit **override** for that organization if there is one (`entitlement_override`: on or
   off, set by a platform administrator, with a reason in the audit trail), otherwise the plan's feature while the subscription is
   `TRIAL` or `ACTIVE`, otherwise off. An unknown feature is off.
3. **One read contract for other modules:** `Entitlements.enabled(feature)` (the organization of the current tenant context) and
   `Entitlements.enabled(tenant, feature)`. The organization comes only from the tenant context, never from a request.
4. **No cache.** Every call reads the database, so the staleness after a change is zero. Sprint 9 decides invalidation when a
   cache of security decisions arrives; this contract is the place it will plug in.
5. **Three separate checks.** A test changes a licence, an entitlement and the administrator marker (the permission of this
   sprint) one at a time and proves the other two do not move, and that an unlicensed administrator can still administer while a
   licensed member cannot (`LicencesIT.aLicenceAnEntitlementAndTheAdministratorMarkerEachChangeWithoutTheOthers`).

## Consequences

- The console shows, per feature, the plan default, the override and the result, so a platform administrator sees why.
- The features exist as keys only; nothing in the product consumes them yet (Sprint 24 consumes `approvals`, Sprint 22
  `workflows`, Sprint 26 `integrations`).

## Verification

`SubscriptionsAndEntitlementsIT` (plan, override, removal, another organization unaffected, unknown feature, the console endpoint
and its audit), `LicencesIT` (the three separate checks).
