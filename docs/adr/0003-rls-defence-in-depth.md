# ADR-0003: Tenant isolation: row level security as defence in depth

- **Status:** Accepted (design). Implemented in Sprint 2.
- **Date:** 2026-10-01
- **Sprint:** S0

## Context

Tenants share one database (pooled model). A single missing `where tenant_id = ?` in application code
would expose one tenant's data to another. Application-level filtering alone depends on every developer and
every query being right, every time. Tenant isolation is a security boundary, not a feature.

## Decision

Isolation is enforced in **two independent layers**; neither is assumed sufficient.

1. **Application layer.** The tenant is never taken from the client. It is derived from the authenticated
   identity, the membership and the host, and carried in a `TenantContext` (tenant, user, membership)
   through requests, asynchronous jobs and events. Repositories still filter by tenant.
2. **Database layer: PostgreSQL row level security (RLS).**
   - Every tenant-scoped table has a non-null `tenant_id`.
   - `ENABLE ROW LEVEL SECURITY` **and** `FORCE ROW LEVEL SECURITY` on every such table.
   - The application connects as a **dedicated non-superuser role that does not own the tables and does not
     have `BYPASSRLS`**. Schema changes run as a separate owner role (ADR-0008).
   - Policies compare `tenant_id` with a **transaction-local** setting
     (`set_config('app.current_tenant', <id>, true)`), set at the start of each transaction from
     `TenantContext`. Transaction-local scope means a pooled connection cannot leak one tenant's setting into
     the next request.
   - If the setting is absent, the policy matches **no rows** (fail closed), never all rows.
3. **Test harness (Sprint 2, reused by every later sprint).** A cross-tenant leak suite plus a
   "forgot the filter" test: a deliberately unfiltered query must still return nothing from another tenant.
   The Definition of Done requires a tenant-isolation test for every new tenant-scoped table or endpoint.

## Consequences

- A coding mistake in the application layer does not become a data breach.
- Every tenant-scoped query pays a small RLS cost; the G1 benchmark (ADR-0002) measures it against a 10% budget.
- Platform-level operations that legitimately span tenants (platform administration, support) must use an
  explicit, audited mechanism; they do not bypass RLS by using a privileged connection.
- Table owners and superusers bypass RLS: connection roles must be reviewed whenever infrastructure changes.
- Background work must set the tenant context explicitly; code that forgets sees no rows rather than all rows.

## Alternatives considered

- **Application filtering only:** rejected, single point of failure.
- **Schema per tenant / database per tenant:** strongest isolation but operational and migration cost
  scales with tenant count; left as a possible later tier for very large tenants.

## References

- Delivery plan, Sprint S2; Definition of Done items 3 and 4; architecture notes 1 (tenant context, isolation).
- Evidence in S0: `PostgresContainerIT` confirms RLS can be enabled on a real PostgreSQL 18 container.
  Policies, roles and the leak suite are Sprint 2 work and are **not yet verified**.
