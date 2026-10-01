# Tenant-isolation tests: how to register a table or an endpoint

The Definition of Done requires a tenant-isolation test for every tenant-scoped table and endpoint, and an authorization test
(allowed, denied, cross-tenant) for every endpoint. Sprint 2 built the harness so that this is a few lines, not a project.
Why it is built this way: [ADR-0015](adr/0015-row-level-security-implementation.md).

## A new table (one registry line)

1. Follow the migration template in [tenancy.md](tenancy.md).
2. Add one entry to `TenantScopedTables` (`platform-app/src/test/java/app/platform/testsupport/tenancy/`): the table name and
   how to insert one valid row **naming the tenant explicitly**:

   ```java
   public static final TenantScopedTable EXAMPLE = new TenantScopedTable("example",
           (connection, tenant) -> TenantFixtures.update(connection,
                   "insert into example (tenant_id, name, created_by, updated_by) values (?, 'probe', ?, ?)",
                   tenant, ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));
   // ... and add EXAMPLE to ALL
   ```

3. Run `./mvnw verify`. `TenantIsolationIT` runs `CrossTenantLeakHarness` against every registered table as the
   unprivileged application role: an unfiltered query returns only the caller's rows; no, empty, malformed or unknown tenant
   sees nothing; another tenant's rows cannot be updated, deleted, inserted or taken over; the tenant of one transaction is
   gone at the next on the same connection; row level security is enabled, forced and has a policy.

Two tests keep this honest. `TenantIsolationIT.everyTableWithATenantColumnIsRegisteredForTheLeakChecks` fails when a table
with a `tenant_id` exists that is not in `ALL`. `SchemaConventionsIT` scans every table of the real schema and fails when a
table is neither tenant-scoped with the full pattern nor listed as platform-level (`SchemaConventions.PLATFORM_TABLES`, a
security decision that a reviewer will see in the diff).

## Proving the harness is not vacuous

`TenantProbeTables.standard(owner, name)` builds a throw-away table exactly to the pattern; `broken(owner, name, defect)`
builds one with a deliberate defect (no row level security, not forced, an open policy, a policy that fails open, open
inserts, the wrong column). `TenantIsolationIT.theLeakChecksFailOnATableBuiltWrong` requires the checks to fail for each. Add
a defect there when a new kind of mistake is possible.

## A new endpoint

Use the same fixtures with HTTP (`TenantResolutionIT` is the model):

- `TenantFixtures.createActiveTenant()` returns a tenant with a host name; `TestHttp.get(path, "Host", tenant.host(), ...)`
  sends a request to that organization (the test client allows the `Host` header).
- **Allowed:** a request to the right organization host (from Sprint 3 with the right user) succeeds and returns only that
  organization's data.
- **Denied:** an unknown, suspended or deactivated organization host is refused (`NOT_FOUND`, `TENANT_UNAVAILABLE`); from
  Sprint 3 an unauthenticated or unauthorized caller is refused.
- **Cross-tenant:** create data for two tenants; a request to tenant A never returns tenant B's data, also when the request
  carries tenant B's identifier in a header, parameter, path segment or body. From Sprint 5, a user with a membership only in
  tenant A is refused on tenant B's host.

## Work outside a request (jobs, events)

Run it under `TenantContexts.run(TenantContext.of(tenantId), ...)` as production does. `TenantFixtures.asTenant(tenant, work)`
runs plain JDBC as the application role in a transaction with the tenant set, which is what the harness itself uses.
For events, `OutboxIT` shows publishing, driving the relay by hand (`pollOnce`), a recording handler, and simulated crashes.
