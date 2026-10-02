# ADR-0027: Which organizations does a person belong to: a narrow system scope

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S5
- **Related:** [ADR-0003](0003-rls-defence-in-depth.md), [ADR-0015](0015-row-level-security-implementation.md),
  [ADR-0026](0026-membership-lifecycle-and-the-administrator-marker.md), [ADR-0029](0029-switching-organizations.md)

## Context

Memberships are tenant-scoped: row level security shows a request only the memberships of the organization its host names. The
organization switcher and the "go to my organization" step must answer a question that spans organizations: *which organizations
does this person belong to?* Two ways were weighed.

## Decision

A **narrow system scope**, `membership_lookup`, owned by the identity module.

- The `membership` select policy admits it next to the tenant: `tenant_id = platform_current_tenant() or
  platform_in_system_scope('membership_lookup')`. Nothing else admits it: no insert, update or delete policy mentions it, no other
  table mentions it, and the tenant guard still stops a row from moving. A test proves the scope reads across organizations and
  cannot write, that another scope (`outbox_relay`) reads no membership, and that without a tenant or a scope nothing is visible.
- Only **one class** (`OrganizationDirectory`) enters it, and every query in it is by user. The architecture test that allowed only
  `tenant` and `outbox` to use the scope enum now also allows `identity`, and a second rule narrows `identity` to that one class.
- A request on an organization host has a tenant context; the scope cannot be entered inside one, so the directory sets the
  request's context aside for the duration of the lookup (`TenantContexts.callAsSystemApart`): refused inside a running
  transaction (the database setting is made when a transaction begins) and inside another scope, restored afterwards.
- The schema scanner's list of tables and scopes that may name a scope is extended in the same change (`membership`,
  `membership_lookup`), so the policy is checked against a recorded decision.

## Trade-off (security, in plain words)

The scope lets this one class read *every* membership row of every organization (it can only select). The code always asks by
user and returns only the organization's identifier, short name, name and the caller's own marker; it never returns another
member's data. A bug in that class could therefore reveal which organizations one person belongs to, not any organization's data,
and not who else belongs. The alternative, a **platform-level index table** (user, organization, status) kept in step in the same
transaction, would never expose the membership table itself, but it is a second copy that can drift (a missing update would leave
a person in the switcher after leaving, or out of it after joining), it needs its own deletion and retention rules, and it holds
personal data in a table without row level security. One source of truth with a narrow, audited, read-only window was judged the
smaller risk.

## Consequences

- The scope's use is logged at debug level like the outbox's; Sprint 9's audit module turns that record into audit rows.
- The switcher answers from live data: a deactivated membership or a suspended organization disappears at once.
- Sprint 6's platform administration needs its own mechanism for cross-organization operations (the request/time-limit/audit model
  of ADR-0003); this scope is not that mechanism and must not be widened for it.

## Verification

`MembershipGuardIT.theMembershipLookupScopeReadsAcrossOrganizationsButCannotWrite`, `SwitchOrganizationIT` (list on both host
kinds, deactivated and closed organizations absent), the architecture tests, `SchemaConventionsIT`, `SystemScopeIT`.
