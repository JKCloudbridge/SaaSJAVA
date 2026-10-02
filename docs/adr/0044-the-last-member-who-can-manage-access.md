# ADR-0044: The last member who can manage access stays (database and service)

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S7
- **Related:** [ADR-0026](0026-membership-lifecycle-and-the-administrator-marker.md) (the rule this moves),
  [ADR-0039](0039-abilities-profiles-access-policies-and-individual-grants.md),
  [ADR-0043](0043-replacing-the-administrator-marker.md)

## Context

Sprint 5 refused to leave an organization without an active administrator (a trigger and an advisory lock on the marker).
With abilities, "administrator" is not a fact on one row any more: several tables together decide who can do what.

## Decision

1. **What is protected: the ability `access.manage` only.** Whoever holds it can give every other ability to anybody, so an
   organization that always has one holder can always repair itself; one that loses its last holder is locked out for good. The
   other administrative abilities are not individually protected (a profile without them is a valid choice).
2. **Who holds it.** An active member holds `access.manage` when one of these gives it: their profile **while they hold the
   licence of its type** (ADR-0045), an access policy assigned to them, or an individual grant. The count is the SQL function
   `platform_access_holders(organization)`.
3. **When it is checked.** Several tables decide who holds it and one action changes several of them (replace a profile: end one
   row, start another), so the rule is checked **once, at commit**: "the organization had at least one holder when this
   transaction first touched these tables and has none now" is refused with a check violation. Organizations that never had a
   holder (being set up, a test fixture) are not blocked.
4. **Concurrency.** A trigger before each change on the tables that decide holders (`membership` status, `profile`,
   `access_policy`, `member_access`, `member_access_policy`, `member_grant`, `licence_assignment`) takes one advisory lock per
   organization (`access-managers:<tenant>`) and remembers the count as it was; the check at commit compares. Two transactions that
   each remove a different holder are therefore decided one after the other, and the second sees the first's result. The services
   take the **same** lock first in every change, so the order of locks is always: access lock, member row, licence pool row.
5. **The service gives the answer in words.** The identity module and the security gate catch the refusal (by recognising the
   guard's text, `LastAccessManager`) and answer `409 CONFLICT` "The organization must keep at least one active member who can manage
   access. Give another member that ability first." — never the database's message.
6. **Tests.** The database rule is tested directly as a database owner without bypass and as the application role, with real
   concurrency (two transactions removing two different holders: exactly one succeeds), for each way of removing a holder
   (deactivate, change profile, remove a licence, edit a profile or policy, revoke a grant, leave), and the old
   "last administrator" tests were rewritten for this rule.

## Consequences

- The key `access.manage` appears in the SQL of V022 and in `Ability.ACCESS_MANAGE`; a unit test keeps them equal.
- Changing what members hold takes a per-organization lock for the length of the transaction. These are rare administrative
  actions, so this is accepted; reads (`Permissions`) never lock.
