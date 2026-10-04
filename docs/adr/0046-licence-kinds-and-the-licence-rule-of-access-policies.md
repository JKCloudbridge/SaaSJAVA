# ADR-0046: Licence kinds and the licence rule of access policies

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S8
- **Related:** [ADR-0032](0032-licences-pools-assignments-and-their-rules.md), [ADR-0039](0039-abilities-profiles-access-policies-and-individual-grants.md),
  [ADR-0045](0045-system-profiles-seeding-backfill-and-licence-consequences.md); delivery plan S6, S7, S8

## Context

Sprint 7 gave every licence-bound access policy a licence of its own, even when it needed the same licence type as the member's
profile. The product owner decided (Sprint 8, question 1): standard policies will be sold on their own licence type; a custom
policy may need any type, also `user` or `admin`; **a policy of the same type as the member's profile uses no extra licence, a
policy of a different type uses one from that type's pool**. The organization buys n admin, p user, ... licences and each use
lowers the free count of that type.

## Decision

1. **A licence type has a kind.** `SEAT` is the right to occupy a seat (`user`, `admin`); `ADD_ON` is sold on top (for example
   the licence of a standard policy). Only a seat can be the licence type of a profile (service answer and database guard); a
   policy may need either. New types default to `SEAT`; the console chooses.
2. **The rule.** A member holds one licence for their profile. A licence-bound policy of the *same* type needs no row of its own;
   one of a *different* type takes one licence of that type, under the pool lock, refused with `409` when none is free.
3. **The policy counts while the member holds a licence of the type it needs** (their profile's or the policy's own). The
   calculator takes this as an input (`PolicyInput.licensed`); `Licences.holdingOf` is the one read.
4. **Changing the profile re-balances in both directions** inside one transaction: policy licences covered by the new profile's
   type go back first, then the profile's licence is taken, then own licences are taken for policies of other types (strict: the
   change is refused with everything undone if one cannot be had). Giving the profile's licence to a member who waited also
   releases the redundant policy licences.
5. **Existing data.** V024 releases the policy licences that the rule makes redundant (only where the member holds the profile
   licence of that type).
6. **Not built yet: standard (platform-defined, sold) access policies.** The mechanism they need (add-on types, the rule above)
   is here; the catalogue of standard policies belongs to the sprint that has something worth selling (objects exist from Sprint
   10). Recorded as deferred.

## Consequences

- The licence still only counts and limits assignments; permissions and features stay separate mechanisms.
- A same-type policy shows "0 licences used" for itself: the licence is the profile's.
