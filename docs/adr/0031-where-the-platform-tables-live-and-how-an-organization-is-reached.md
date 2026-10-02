# ADR-0031: Where the Sprint 6 tables live, and how a platform administrator reaches one organization

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S6
- **Related:** [ADR-0003](0003-rls-defence-in-depth.md), [ADR-0015](0015-row-level-security-implementation.md),
  [ADR-0022](0022-platform-level-identity-tables-and-audit-v0.md), [ADR-0027](0027-which-organizations-does-a-person-belong-to.md),
  [ADR-0030](0030-platform-roles-and-the-first-platform-administrator.md)

## Context

Sprint 6 adds eleven tables. Every table is either tenant-scoped (row level security, ADR-0015) or declared platform-level in
a security decision. The console must also (a) list organizations with their plan and trial across all of them and (b)
change one organization's pools and support-access records, both **without a privileged connection and without a broad system
scope** (ADR-0003, ADR-0027). The recommendation of the sprint prompt was adopted: reach one organization at a time through
its own tenant context, and read cross-organization lists only from platform-level tables.

## Decision

**Platform-level** (no tenant column, no row level security; each is listed in `SchemaConventions.PLATFORM_TABLES` and in
the test that asserts the list exactly):

| Table | Why platform-level |
|-------|--------------------|
| `platform_role_assignment` | a role belongs to a person, not to an organization (ADR-0030) |
| `licence_type`, `feature`, `plan`, `plan_licence`, `plan_feature` | the vendor's catalogue: identical for every organization, edited only by platform administrators |
| `subscription` | the vendor's commercial fact about an organization; the console lists it across organizations (expiring trials, plans) |
| `entitlement_override` | the vendor's switch of one feature for one organization |

The last two name the organization in `bound_tenant_id` (never `tenant_id`), so a reader sees at once that **row level security
does not protect them**.

**Tenant-scoped** (tenant column, forced row level security, guard triggers, tenant-first index, registered in
`TenantScopedTables` so the leak harness covers them): `licence_pool`, `licence_assignment` (V015),
`support_access_grant` (V017). They are the organization's own records.

**How a platform person reaches one organization.** The code (`OrganizationScope` in `platformadmin`) opens that **one
organization's tenant context before the transaction begins**, so row level security limits every statement of the transaction
to it; the platform person travels in the context (logs and records name them) and the audit record names both the platform
actor and the target organization. The organization named in a path is a destination chosen by an authorized platform person;
it is never read from a header, parameter or body as "the tenant of the request" (a test forges a tenant header on a platform
endpoint and on an organization endpoint: nothing changes). **Cross-organization lists** (the console's organization list)
come from the platform-level `tenant` and `subscription` tables only. No new system scope exists: the scope list is unchanged
(`membership_lookup`, `outbox_relay`) and an architecture rule still keeps platform administration out of it.

## Trade-off (security, in plain words)

`subscription` and `entitlement_override` have no row level security. A bug in the licensing code that asked for the wrong
organization could therefore read another organization's plan, trial end or feature switch: commercial facts the vendor itself
set, never any of the organization's data. Making them tenant-scoped would have forced a system scope (or one query per
organization) for the console's list, which is the wider risk the decision avoids. The organization's own code never names an
organization for these tables: `Entitlements.enabled(feature)` and the licence service take it only from the tenant context.

## Consequences

- Adding a table to either group is a recorded decision (this table, the scanner list and the harness list change together).
- A platform administrator's write to a pool or a support-access record is bound by row level security to the one
  organization whose context was opened; writing to two organizations at once is impossible by construction.
- The pool quantity is tenant-scoped although a platform administrator sets it: the platform person's write goes through
  that organization's context, and the organization's administrators cannot change it (they have no endpoint for it).

## Verification

`SchemaConventionsIT` (the exact list of platform-level tables, the pattern of the three tenant-scoped ones), `TenantIsolationIT`
(the leak harness on `licence_pool`, `licence_assignment`, `support_access_grant`), `PlatformRolesIT` (forged tenant header),
`LicencesIT` (a foreign member and a forged header on an organization endpoint), `SystemScopeIT` and the architecture tests
(no new scope).
