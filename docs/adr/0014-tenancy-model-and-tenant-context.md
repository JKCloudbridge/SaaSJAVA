# ADR-0014: Tenancy model, tenant lifecycle and the tenant context

- **Status:** Accepted
- **Date:** 2026-10-01
- **Sprint:** S2
- **Implements:** [ADR-0003](0003-rls-defence-in-depth.md) (application layer). Database layer: [ADR-0015](0015-row-level-security-implementation.md)

## Context

Every later table, request and job belongs to one organization (a tenant). The platform needs one definition of "which
tenant is this work for", one place that carries it, and a rule for what happens when it is missing. If code finds the
tenant in different ways (a header here, a parameter there), isolation depends on every developer, every time, which is
the situation ADR-0003 exists to prevent.

## Decision

1. **Pooled tenancy.** All tenants share one database and one schema. Isolation is the application layer in this ADR
   plus row level security (ADR-0015). Schema or database per tenant remains a possible later tier (ADR-0003).
2. **The `tenant` table is platform-level**, not tenant-scoped: no `tenant_id`, no row level security, base columns and the
   row guard (ADR-0010). The platform must look a tenant up by host name before any tenant context exists. A test lists the
   platform-level tables, so a table is tenant-scoped unless someone deliberately declares otherwise.
3. **Lifecycle** `PROVISIONING -> ACTIVE <-> SUSPENDED`, any of these `-> DEACTIVATED` (final). Only ACTIVE accepts requests.
   Enforced twice: by `TenantStatus` and `Tenants` in the module, and by a database trigger
   (`platform_tenant_status_guard`) so a hand-written statement cannot skip a step; a test tries every pair of states
   against both and requires them to agree. Each operation names the states it applies to (activating is not
   reinstating, although both end in ACTIVE; a test found that mistake).
4. **Slug.** 3 to 40 characters, lower-case letters, digits and single hyphens, starting with a letter and ending with a
   letter or digit; unique among live tenants (partial unique index); the database repeats the format as a check
   constraint. A list of reserved names (`www`, `api`, `admin`, `support`, `localhost`, `tenant`, ...) is not available as
   slugs and is treated as the platform's own hosts (ADR-0017). Rejected input is never echoed back.
5. **`TenantContext(tenantId, userId, membershipId)`.** The user and the membership are empty until Sprints 3 and 5; a
   membership implies a user. The tenant is always derived on the server: from the host name now, validated against the
   authenticated identity and membership from Sprint 5. It is never read from a header, parameter, path segment or body.
6. **The context is held by a bean** (`TenantContexts`, an ordinary Spring bean with a per-thread frame as an instance field),
   not by static state. It is opened with a scope that restores exactly what was there before, also when the work fails.
   While a context is open the tenant ID is in the logging context of the thread (`LogContext.TENANT_ID`, set only here).
7. **Rules of the holder, enforced and tested:**
   - Open the context **before** the transaction begins. The database setting is made at the start of a transaction
     (ADR-0015); changing the tenant inside a running transaction is refused with an `IllegalStateException`, because the
     old tenant would stay in force in the database. Opening the same tenant again (or changing only the user) is allowed.
   - A thread works for a tenant **or** in a system scope (ADR-0015), never both.
   - Work handed to another thread is wrapped (`propagate`) or runs on an executor that uses the module's
     `TaskDecorator`; the context of the submitting thread is captured and the running thread's own is restored afterwards,
     so a pooled thread never keeps the context of its previous task. An unwrapped task has no tenant and sees no rows.
8. **Events carry the context.** Every outbox event stores tenant, user and membership; the relay restores them while a
   handler runs (ADR-0016).
9. **Lifecycle changes publish events** (`tenant.provisioned`, `tenant.activated`, `tenant.suspended`,
   `tenant.reinstated`, `tenant.deactivated`) in the same transaction as the change, under the changed tenant's own context.
   The audit module (Sprint 9) consumes them.
10. **`Tenants` performs no authorization.** There is no HTTP entry point that creates or changes tenants in this sprint
    (decision of the project owner, see below); the authenticated entry points arrive with sign-up (Sprint 4) and platform
    administration (Sprint 6), and each decides who may call. The one endpoint added, `GET /api/v1/tenant/current`,
    only reports the name of the organization the host addresses.
11. **Module.** `tenant` (`app.platform.tenant`, allowed dependency: `sharedkernel`). Public API: `Tenants`, `Tenant`,
    `TenantStatus`, `TenantSlug`, `TenantContext`, `TenantContexts`, `SystemScope`. Everything else is internal.

## Decisions taken by the project owner at the start of Sprint 2

| Question | Answer |
|----------|--------|
| May a tenant be created through an endpoint in this sprint? | **No.** Tenants are created through the service by tests and, on a developer machine only, by a seed of the `local` profile (`tenant-a`, `tenant-b`). |
| Which error code for a closed organization? | **New code `TENANT_UNAVAILABLE` (HTTP 403)**; an unknown organization stays `NOT_FOUND` (404). Added through the documented process (code, frozen-list test, OpenAPI document, generated client, frontend). |
| Slug rules and reserved names? | As in point 4. |
| May the API take the host from `X-Forwarded-Host`? | **Only through a switch**, off by default, on in the `local` profile (ADR-0017). |

## Deferred (must not be forgotten; also in the delivery plan and the sprint documents)

- Sprint 3: security exception handlers (401, 403) in the global exception handler.
- Sprint 4: the first authenticated way to create a tenant (new-organization sign-up) through `Tenants.provision`.
- Sprint 5: validate the host's tenant against the authenticated membership; populate `userId` and `membershipId`.
- Sprint 6: platform-administration operations (suspend, reinstate, deactivate) with authorization and audit.
- Sprint 9: the audit module consumes the lifecycle events.
- Later: custom domains (an extension point only, ADR-0017).

## Consequences

- Forgetting the tenant is not a data leak: without a context a transaction sees no tenant rows (ADR-0015).
- Code that needs the tenant asks the holder; it never parses a request.
- A caller that already runs a transaction must have opened the tenant's context before starting it. The sign-up flow
  (Sprint 4) uses `Tenants.newId()` and `provision(id, ...)` for exactly this reason.
- The per-thread frame costs nothing measurable; the extra round trip per transaction (the database setting) is part of
  the Sprint 14 benchmark.

## Alternatives considered

- **A static holder (like the logging context):** simpler to call, but global state that tests cannot isolate.
- **Passing the tenant as a method parameter everywhere:** explicit, but one forgotten parameter or one wrong value is the
  mistake the platform must survive; the context plus row level security makes the default safe.
- **Resolving the tenant in each controller:** repeats the security decision in every endpoint.

## References

- `platform-app/src/main/java/app/platform/tenant/`
- `platform-app/src/test/java/app/platform/tenant/` (`TenantContextsTest`, `TenantContextSessionIT`, `TenantLifecycleIT`,
  `TenantResolutionIT`), `platform-app/src/test/java/app/platform/testsupport/tenancy/`
- Architecture notes: tenant context, never trust the tenant ID from the browser (notes 1 and 2).
