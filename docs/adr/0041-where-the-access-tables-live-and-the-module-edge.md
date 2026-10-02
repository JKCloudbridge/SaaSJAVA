# ADR-0041: Where the access tables live, and the module edge identity to security

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S7
- **Related:** [ADR-0014](0014-tenancy-model-and-tenant-context.md), [ADR-0015](0015-row-level-security-implementation.md),
  [ADR-0031](0031-where-the-platform-tables-live-and-how-an-organization-is-reached.md),
  [ADR-0039](0039-abilities-profiles-access-policies-and-individual-grants.md), `docs/modules.md`

## Decision: every new table is tenant-scoped

`profile`, `access_policy`, `security_role`, `member_access` (the profile and role of a member), `member_access_policy` and
`member_grant` all belong to one organization and are edited by that organization, so each has `tenant_id` (default
`platform_current_tenant()`), row level security **enabled and forced** with a policy on `platform_current_tenant()`, the tenant
guard trigger, a tenant-first index, the base columns and `platform_row_guard`, and each is registered in `TenantScopedTables`
(so it gets the isolation test). **No table of this sprint is platform-level**: the only platform-level thing is the catalogue of
abilities, and that lives in the code (ADR-0039), not in a table. `licence_type` stays the platform catalogue of Sprint 6; a
profile and a policy point at it.

A foreign key check bypasses row level security, so a row could name a profile, role, policy or member of **another**
organization. Each table therefore has a guard trigger that checks the named rows are live rows of the same organization (and that
a member is active), which is also why the service layer never needs to trust an identifier from the browser: a foreign
identifier is simply not found.

## Decision: the module edge is reversed (identity depends on security)

The first draft of `docs/modules.md` said `security` depends on `identity`. That would make a cycle: `Administration` (in identity)
must ask security what a member may do, and acceptance, founding, deactivation and reactivation must create, change and release a
member's access in the **same transaction** as the membership change. As in Sprint 6 for licensing, the edge is flipped:
`identity → security → licensing → tenant`, and `security` no longer depends on `identity`. Security knows a member only by its
membership identifier; identity checks the member exists and is active before it calls (and the database refuses anything else),
and the caller of a request is read from the tenant context (user and membership), which belongs to the `tenant` module.
`platformadmin` already depended on `security` (it seeds the system profiles when it provisions an organization, ADR-0045).
The architecture tests enforce the new graph; `docs/modules.md` and both `package-info.java` files say the same.

A DB guard may read the `membership` table (it did since Sprint 5 for licences); Java code in `security` never does.

## How a platform administrator provisions an organization's profiles

Provisioning opens that one organization's tenant context before the transaction (as for pools, ADR-0031) and calls
`MemberAccess.ensureSystemProfiles`. No privileged connection and no new system scope is involved.
