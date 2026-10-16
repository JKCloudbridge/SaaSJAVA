# ADR-0135: The workflow dispatch system scope (an amendment to ADR-0015)

- **Status:** Accepted
- **Date:** 2026-10-16
- **Sprint:** S22
- **Amends:** [ADR-0015](0015-row-level-security-implementation.md) (decision 6: "no other table mentions it")
- **Related:** ADR-0026 to ADR-0029 (the `membership_lookup` scope this one is modelled on),
  [ADR-0134](0134-the-scheduler-of-timed-workflows-decision-d9.md), [ADR-0137](0137-executions-asynchronous-after-commit-and-what-they-keep.md)

## Context

Two things must be found across organizations without knowing the organization first: **which schedule is due** and **which
queued execution is next**. Both are "the oldest row that is due, of whichever organization has it", answered by one indexed
statement. ADR-0015 allows work across tenants only through named system scopes and lists them (`outbox_relay`,
`membership_lookup`); the periodic jobs of the platform so far avoided a scope by walking the list of organizations and opening
each one's own context (`RecycleBinRetention`). That works for a handful of fixed jobs. For schedules and a work queue it makes
the cost grow with the number of organizations instead of the number of due rows, and it cannot claim with `SKIP LOCKED` across
instances in one statement.

The first plan asked for a scope for the schedule table only. The work queue needs the same thing for the same reason, so the
scope covers two tables, and says so here.

## Decision

1. **One new scope, `workflow_dispatch`** (`SystemScope.WORKFLOW_DISPATCH`, setting value `workflow_dispatch`, not audited per
   use: it is entered every second on every instance, like `outbox_relay`).
2. **Two tables admit it, and no other:**
   - `workflow_execution`: select, update (claim, and the outcome of a claim) and delete (retention);
   - `workflow_schedule`: select and update (claim).
   Inserting a row for an organization is never admitted by a scope: executions and schedules are made inside the organization's
   own context. The tenant guard still stops a row from moving.
3. **The scope claims; it never works.** Claiming returns identifiers (the row, the organization, the workflow version, the
   record). The work (read the definition, read the record, judge the condition, run the action, write the outcome) always runs
   inside the claimed row's own tenant context and transaction. Nothing a record holds is read in the scope.
4. **Only the dispatcher may enter it.** An architecture test (`PlatformRulesTest`) allows `app.platform.workflow.internal.Dispatcher`
   to reference `SystemScope` and `DispatchStore` to hold the statements; any other class of the module fails the build, and the
   rule that only `tenant`, `outbox`, `identity` (one class) and now `workflow` may name the enum stays.
5. **The scanner knows it.** `SchemaConventions` lists the two tables and the scope; a policy that names another scope or another
   table fails `SchemaConventionsIT`. `SystemScopeIT` lists the rights of the scope: it reads and claims across organizations on
   the two tables, cannot insert a row for an organization, cannot move a row, and sees nothing else.
6. **Retention** of finished executions finds the organizations that have expired rows (a select in the scope) and then removes
   the rows organization by organization in the organization's own context, so that the audit record of the pass is written in
   that context. The platform-wide `delete` policy exists so a future global clean-up does not need another amendment; this
   release does not use it for executions (the schedule table has no scope delete).

## Consequences

- Cost of finding work is the cost of an index range, independent of how many organizations exist.
- The set of things that can work across organizations is now: the outbox relay, the membership lookup, and this dispatcher. Each
  is one class, one scope, a short list of tables, and a test that proves it.
- A bug in the dispatcher could claim another organization's rows (and nothing else). It still cannot read a record, a
  definition or a value: the tables hold identifiers, a version number, a reason key and the type name of a failure (ADR-0137).

## Alternatives considered

- **Walk the organizations every tick:** cost proportional to organizations, no cross-instance claim in one statement, an index on
  "due" cannot be used (ADR-0134).
- **A `SECURITY DEFINER` function:** the owner is bound by `FORCE`, so it would need `BYPASSRLS`, a privileged role to protect
  everywhere; the named scope keeps one role (ADR-0015).
- **Putting the scheduler in a platform-level table without row level security:** tenant isolation would rest on code alone.
- **A scope for the schedule table only and a tenant walk for executions:** two mechanisms for one need.

## References

- `platform-app/src/main/java/app/platform/tenant/SystemScope.java`, `workflow/internal/Dispatcher.java`, `DispatchStore.java`
- `platform-app/src/main/resources/db/migration/V053__create_workflows_and_executions.sql`
- `platform-app/src/test/java/app/platform/database/SystemScopeIT.java`, `SchemaConventions.java`,
  `architecture/PlatformRulesTest.java`
