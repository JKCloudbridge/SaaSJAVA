# ADR-0047: Public groups: nesting, access policies, loops

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S8
- **Related:** [ADR-0040](0040-effective-permissions-and-the-read-contract.md), [ADR-0042](0042-the-role-hierarchy.md),
  [ADR-0044](0044-the-last-member-who-can-manage-access.md), [ADR-0048](0048-groups-and-the-last-holder.md); architecture note 2
  section 17; delivery plan S8

## Context

The written design (note 2, section 17) describes public groups as containers for record sharing, approvers, assignment, notifications
and application access, with nesting and no circular references. It does **not** make groups an input to the effective-permission
formula. The product owner decided (Sprint 8, question 4) that a group can carry access policies, so everyone in it gets what the policy
gives. This is an addition to the written design and is recorded as such in the delivery plan.

## Decision

1. **Tables** (all tenant-scoped, ADR-0015): `public_group`, `public_group_member` (a person or a group; exactly one),
   `public_group_access_policy`. Names are unique per organization, case-insensitively.
2. **A member's policies** = their assigned policies plus the policies of every group they are in, directly or through nested
   groups, once each, as an ordinary union (ADR-0040: no deny). Roles take no part.
3. **No loops, twice.** The service refuses adding group C to group G when G is C or reachable by going down from C. The database
   repeats it in a trigger under one advisory lock per organization (taken after the access lock, so the order of locks is
   always: access lock, group lock, member row, pool row). Two administrators adding the two halves of a loop at once have one
   winner. Walking the nesting uses recursive queries that remove repeats, so they terminate even on bad data.
4. **A policy that needs a licence is never given to a group** (a licence belongs to a person). A policy a group uses is "in use": it
   is not removed and keeps its licence requirement.
5. **Membership ends with the person.** Deactivating or leaving takes a person out of every group (audited, reason
   `membership_ended`); a returning member starts afresh (ADR-0045). Removing a group ends its links in the same transaction.
6. **Only people with `access.manage`** read or change groups (no new ability). Group endpoints answer on organization hosts only.
7. **`GroupMembers`** (security root) answers "which groups is this member in" and "who is in this group" with nesting resolved, for
   record sharing (Sprint 17) and approvals (Sprint 24).

## Consequences

- The last-holder guard (ADR-0044) now also follows groups (ADR-0048).
- A group page that needs member names reads the member list (`members.view`); the security module only knows members by identifier.
