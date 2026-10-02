# ADR-0033: Subscriptions, "try for free" trials, and the limit of organizations per person

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S6
- **Related:** [ADR-0025](0025-founding-an-organization-and-the-minimal-membership.md),
  [ADR-0031](0031-where-the-platform-tables-live-and-how-an-organization-is-reached.md),
  [ADR-0032](0032-licences-pools-assignments-and-their-rules.md)

## Context

Sprint 4 lets a signed-in person found an organization with no plan and no trial. The product needs a place to say which plan an
organization is on, whether it is a trial and when it ends, without coupling to a payment processor (there is none yet), and
without anything switching off by itself until the scheduler of Sprint 25 exists.

## Decision

1. **A subscription per organization** (`subscription`, platform-level, ADR-0031): a plan, a status (`TRIAL`, `ACTIVE`,
   `SUSPENDED`, `CANCELLED`), a start, an optional end of trial and an optional end of the paid period. One live subscription
   per organization; changing the plan updates it. No payment data, no processor.
2. **"Try for free."** An organization founded by a signed-in person starts on the default plan (`platform.licensing.default-plan`,
   seeded as `trial`: 30 days, 5 `user` and 2 `admin` licences, the four first features) with status `TRIAL`, its pools are created
   from the plan, and the founder holds the default licence. It happens in the founding transaction, so there is never an
   organization without one. A platform-provisioned organization starts on the plan the administrator chose (a trial when the plan
   has trial days, otherwise active).
3. **What happens when a trial ends: it is recorded and shown, nothing switches off.** The console shows a trial as
   `expiring`/`expired` (`trialExpired`), and a platform administrator or billing person decides (move to a paid plan, set the
   subscription `SUSPENDED` or `CANCELLED`, extend the date, suspend the organization). The scheduler of Sprint 25 will automate
   it. A subscription that is `SUSPENDED` or `CANCELLED` turns the plan's features off (ADR-0034); the people are not locked out.
4. **A change of plan resizes the pools** to the new plan's quantities in one transaction; if any pool would end below the
   licences in use, nothing changes (`409`).
5. **The limit of 3 organizations per person stays a setting** (`platform.identity.account.max-organizations-per-person`), not a
   plan attribute; it concerns a person, not an organization's plan.

## Consequences

- The platform cannot yet end a trial by itself or take payment; both are recorded as later work (Sprint 25 and a later billing
  sprint).
- An organization with no subscription (none exists after V018 and the founding change) would simply have no plan features.

## Verification

`SubscriptionsAndEntitlementsIT` (founding gives a 30-day trial, pools and the founder's licence; an expired trial is shown and
nothing switches off; plan change resizes and a plan below use is refused as a whole; validation of the catalogue),
`MigrationIT` (backfill).
