# ADR-0048: Groups and the last member who can manage access

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S8
- **Related:** [ADR-0044](0044-the-last-member-who-can-manage-access.md), [ADR-0046](0046-licence-kinds-and-the-licence-rule-of-access-policies.md),
  [ADR-0047](0047-public-groups.md)

## Context

ADR-0044 protects the ability `access.manage` by counting, at commit, the active members who hold it through a licensed profile, an
access policy or an individual grant. Since Sprint 8 the ability can also arrive through a group (nested to any depth) and a
licence-bound policy counts only while the member holds a licence of its type.

## Decision

1. `platform_access_holders(organization)` (V025) counts a member as a holder when any of these gives them the ability: a licensed
   profile; an assigned policy (a licence-bound one only while they hold a licence of its type, which the profile's licence may be);
   a policy given to a group they are in (a recursive walk upward through live groups); an individual grant.
2. The tables that decide it are watched exactly like the others: a before-trigger takes the per-organization lock and remembers
   the count, a deferred trigger compares at commit. New: `public_group`, `public_group_member`, `public_group_access_policy`.
3. The services give the answer in words (`409`, "must keep at least one active member who can manage access"), through the same
   recogniser, for every way of removing a holder through a group: taking the person out, cutting a nested chain, taking the policy
   from the group, removing the group, editing the policy, deactivating the member.
4. The lock order is unchanged and extended: access lock, group lock, member row, pool row.

## Tests

`GroupGuardIT` (database, two-level nesting, every way of removing the last holder) and `GroupIT` (service, concurrency: two
managers removing each other's group access at once leave one). Each guard was checked to fail under a quick mutation (see `Sprint 8.md`).
