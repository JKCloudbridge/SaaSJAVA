# ADR-0015: Row level security: roles, policies, system scope and what was verified

- **Status:** Accepted
- **Date:** 2026-10-01
- **Sprint:** S2
- **Implements:** [ADR-0003](0003-rls-defence-in-depth.md) (database layer). Supersedes nothing.

## Context

ADR-0003 decided that tenant isolation has a database layer and listed its ingredients. This ADR records how they were
built, in what shape every later table must follow, and, because ADR-0003 marked the policies and the leak suite as "not
yet verified", what was actually proven and what was not.

## Decision

1. **Two roles** (ADR-0009). The *owner* migrates and owns every object. The *application role* `platform_app` is what
   the running application connects as. It is created by the **manual migration M001**
   (`db/manual/M001__create_application_role.sql`), run once per environment as the owner. It is not a superuser, cannot
   create databases or roles, does not bypass row level security (`NOBYPASSRLS`), owns nothing and has no schema rights. Its
   password is **never in a file**: M001 creates the role without one, and a person sets it with `\password`. The test
   harness and the CI smoke test run the real M001 script and set the password separately, so what is tested is what is
   deployed. M001 can be run again safely (it re-asserts the role's attributes and the grants) and stops with a message
   when run by a role that is not the owner.
2. **Grants by default privileges.** M001 gives the application role `SELECT, INSERT, UPDATE, DELETE` on tables and `USAGE,
   SELECT` on sequences the owner creates **from then on** (`alter default privileges`), and the same on what exists. No
   migration grants anything. Deliberately **not** granted: `TRUNCATE` (it ignores row level security), `REFERENCES`,
   `TRIGGER`, and anything on the migration tool's history table. The history table is created by the tool before any
   migration, so it receives the default privileges; migration `V002` takes every right on it away from every role except
   the owner, whatever the application role is called. Tests check each of these facts.
3. **The shape of a tenant-scoped table** (a table with a `tenant_id` column), after the base conventions of ADR-0010:
   ```sql
   tenant_id uuid not null default platform_current_tenant() references tenant (id)
   alter table t enable row level security;
   alter table t force  row level security;
   create policy ... using (tenant_id = (select platform_current_tenant()))
                     with check (tenant_id = (select platform_current_tenant()));
   create trigger t_tenant_guard before update on t for each row execute function platform_tenant_guard();
   create index t_tenant on t (tenant_id, ...);
   ```
   `FORCE` binds the table owner too; without it the owner would bypass the policy. The policy compares with a function, in a
   sub-select so that PostgreSQL evaluates it once per statement, not once per row. `platform_tenant_guard()` refuses any
   update that changes `tenant_id`: a row never changes owner. The index starts with `tenant_id` because every isolated query
   filters by it.
4. **The setting.** The application sets the transaction-local setting `app.current_tenant` at the start of every
   transaction from the tenant context (`TenantSessionListener`, the only place in the code that names it; an architecture
   test scans the sources). It uses `set_config(..., true)`, so the setting ends with the transaction and a pooled connection
   cannot carry it into the next request. `platform_current_tenant()` returns the tenant or **null**; a setting that was never
   set, was reset by a finished transaction (PostgreSQL then reports an empty string, not "unset"), or holds something that
   is not a UUID all mean "no tenant", and none of them raises an error that could quote the value. A comparison with null
   matches no row: **fail closed**.
5. **Not best effort.** Unlike the correlation stamp of ADR-0012, a failure to set the tenant marks the transaction
   rollback-only (and the failed statement has already aborted it): work never runs with a missing or wrong tenant.
6. **The system scope** is the only way to work across tenants, and it is not a bypass. It is a second transaction-local
   setting (`app.system_scope`) naming the kind of platform work. Policies admit it per table and per command:
   - `outbox_event`: read, update (record the outcome) and delete (retention) across tenants;
   - `processed_event`: read and delete (retention); insert and update stay with the tenant;
   - no other table mentions it. Inserting a row for a tenant is never admitted by a scope, and the tenant guard stops
     moving a row.
   A scope is a constant of the `SystemScope` enum, opened through `TenantContexts.callAsSystem`; a thread cannot be in a
   scope and a tenant context at once; only the `tenant` and `outbox` modules may use the enum (architecture test); entering
   one is logged (at debug level; the relay logs a line only when it handles or fails events). The audit module (Sprint 9)
   turns that record into audit rows. Support access to tenant data (Sprint 6)
   will not use this mechanism as it is; it needs its own request, time limit and audit (ADR-0003, consequences).
7. **Enforcement by test.** The schema-conventions scanner (ADR-0010) now also requires, for every table in `public`:
   either `tenant_id uuid not null` with row level security **enabled and forced**, at least one policy whose expressions
   compare `tenant_id` with `platform_current_tenant()` and contain no literal `true`, exactly one tenant guard trigger, and
   an index starting with `tenant_id`; or an entry in the list of platform-level tables. Policies naming a system scope are
   checked against a list of the tables and scopes allowed to. The scanner is proven on twelve deliberately bad tables.
8. **The cross-tenant leak harness** (`testsupport/tenancy`), reused by every later sprint, runs as the application role:
   an unfiltered query returns only the caller's rows ("forgot the filter"); no, empty, malformed or unknown tenant sees
   nothing; updates and deletes of another tenant's rows touch nothing; a row for another tenant cannot be inserted or
   moved; the tenant of a finished transaction is not in force on the next one on the same connection; row level security is
   enabled, forced and has a policy. A table is registered with one line (`TenantScopedTables`), and a test fails when a table
   with a `tenant_id` exists that is not registered. How to use it: [../tenant-isolation-testing.md](../tenant-isolation-testing.md).

## What was verified (by running it, on PostgreSQL 18 in a container, as the application role)

| Claim | Test |
|-------|------|
| A deliberately unfiltered query cannot cross tenants | `TenantIsolationIT.aDeliberatelyUnfilteredQueryStillCannotCrossTenants`, and the harness on every registered table |
| With no tenant setting the policy matches no row; empty, malformed and all-zero settings too | `withNoTenantSettingTheTenantPolicyMatchesNoRowsAtAll`, harness |
| The setting does not leak between pooled connections | `TenantContextSessionIT.theTenantNeverLeaksBetweenRequestsSharingPooledConnections` (400 tasks, 24 threads, pool of 10), harness `noLeakBetweenTransactions` |
| The harness fails on a table built wrong | `TenantIsolationIT.theLeakChecksFailOnATableBuiltWrong` (six defects); and a temporary removal of `FORCE` from `outbox_event` failed three tests, then was restored |
| `FORCE` binds a non-superuser owner, and an unforced table does not | `forcedRowSecurityBindsTheTableOwnerAndAnUnforcedTableDoesNot` |
| The application role cannot disable or weaken the protection, truncate, own objects, or reach the migration history | `DatabaseRolesIT` |
| Default privileges alone make a new owner-created table usable | `aTableTheOwnerCreatesLaterIsUsableByTheApplicationRoleThroughDefaultPrivilegesAlone` |
| The system scope reads across tenants only where admitted and cannot write or move rows | `SystemScopeIT` |
| M001 can be re-run and refuses a stranger | `DatabaseRolesIT` |

## Known limits (stated, not hidden)

- **Row level security protects against mistakes, not against a compromised application.** The application role must be
  able to set the setting, so code that is subverted can name any tenant. A test documents this. Authentication,
  membership validation (Sprint 5) and review cover that case.
- **Superusers and roles with `BYPASSRLS` ignore all policies.** The integration tests' owner is a superuser (the
  container's bootstrap role), so they prove the application role's behaviour, and `FORCE` is proven separately with a plain
  owner. A deployment's owner and application roles must not be superusers; a review point whenever infrastructure changes
  (ADR-0003).
- **Migrations that touch tenant data as the owner see nothing** unless they set the tenant setting for the transaction
  (forced policies bind the owner). Data migrations on tenant tables must do so deliberately, per tenant.
- **Cost.** Not measured. The Sprint 14 benchmark (gate G1) must include the policy cost and the two extra round trips per
  transaction (the correlation stamp and the tenant setting), against the 10 percent budget of ADR-0002.
- **The hostname lookup is one query per request** and is not cached (ADR-0017).

## Consequences

- A forgotten filter or a forgotten tenant returns nothing instead of another tenant's data.
- Every new table must follow the pattern or the integration build fails; the cost is a migration template and one registry
  line in the test support.
- Platform work across tenants is rare, named and visible.
- Local development needs the application role too (`infra/local/init-app-role.ps1`).

## Alternatives considered

- **Policies that read the tenant from a session-level setting:** leaks between pooled requests; rejected (ADR-0003).
- **A database role per tenant:** strongest isolation, but the pool, the migrations and the number of roles scale with
  tenants.
- **A `SECURITY DEFINER` function for the relay's cross-tenant reads:** the function's owner is also bound by `FORCE`, so it
  would need `BYPASSRLS`, a privileged role that must then be protected everywhere; the named scope keeps one role.
- **Disabling policies for platform work:** the whole point of the platform is that nothing is switched off.

## References

- `db/manual/M001__create_application_role.sql`, `platform-app/src/main/resources/db/migration/V002`, `V003`, `V004`
- `platform-app/src/main/java/app/platform/tenant/internal/TenantSessionListener.java`
- `platform-app/src/test/java/app/platform/database/` (`TenantIsolationIT`, `SystemScopeIT`, `DatabaseRolesIT`,
  `SchemaConventionsIT`, `SchemaConventions`), `platform-app/src/test/java/app/platform/testsupport/tenancy/`
- PostgreSQL documentation: row security policies, `set_config`, default privileges (the reference documents of the project).
