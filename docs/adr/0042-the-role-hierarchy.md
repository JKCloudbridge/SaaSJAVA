# ADR-0042: The role hierarchy: visibility only, no loops

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S7
- **Related:** [ADR-0039](0039-abilities-profiles-access-policies-and-individual-grants.md),
  [ADR-0040](0040-effective-permissions-and-the-read-contract.md); delivery plan S7, S8 and S17; architecture note 2, section 15

## Context

The written design separates *what can you do* (profile, access policies, grants) from *which records might you see* (roles). A
role must not become a second way to give abilities: that is hard to manage at scale.

## Decision

1. **A role is a node in a tree per organization** (`security_role` with an optional `parent_id`; a name unique per organization).
   A member holds **zero or one** role (`member_access.role_id`).
2. **A role decides no ability.** It is not an input of the calculator (ADR-0040) and no check reads it. Today it has **no effect at
   all** on what anyone can do; it is stored so that Sprint 8 and Milestone 4 can use it. What they will use it for: the
   organization-wide default and **role-hierarchy access** of record visibility in Sprint 17 ("a manager sees the records of the
   people below"), and, if adopted, as a context for sharing rules and layouts. Nothing in Sprint 7 may be changed by assigning or
   moving a role, and a test proves it.
3. **No loops, in the service and in the database.** The service walks up from the new parent and refuses a move below the role
   itself or one of its sub-roles with `409`; a trigger on the table does the same walk, under one advisory lock per organization,
   so two administrators moving roles at the same moment cannot together make a loop (each move is valid alone). The same trigger
   refuses a parent that is not a live role of the same organization and a depth over 50. A role with sub-roles or with members
   cannot be removed.
4. **Who:** members who manage access change roles and assign them; members who may see members or invite members read the list
   (to choose a role for a new member).

## Consequences

An organization can model its structure now and nothing changes for anyone; when records exist the same tree starts to matter. If
a later sprint needs several roles per member or a different shape, that is a new migration, not a rewrite of abilities.
