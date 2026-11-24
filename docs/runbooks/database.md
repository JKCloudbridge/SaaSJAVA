# Runbook: the database

Sprint 33 (decisions in [ADR-0293](../adr/0293-the-database-the-pool-and-failover.md) and
[ADR-0288](../adr/0288-migrations-as-a-job-the-compatibility-rule-and-rollback.md)).

## When to use this

First start of an environment, creating the application role and the first platform administrator, a failover, a restore, or when
the number of tasks or the pool size changes.

## First start of an environment *(cloud)*

1. The infrastructure files made the database (PostgreSQL 18) and the owner role (the service's master user; its password is kept by
   the service). The owner reads it from the secret the service keeps; it never goes into a file.
2. **M001, once per environment, as the owner, before the first start:** connect to the database through a host inside the network
   (the database is not reachable from the internet; use a task or a bastion you create for the purpose and remove afterwards) and
   run `db/manual/M001__create_application_role.sql` with `psql -v ON_ERROR_STOP=1`. In the same session set the application role's
   password with `\password platform_app` (the tool asks twice and never shows it) and store the same value in the secret store entry
   `db-app-password`. Check that the role has no schema rights: `select rolsuper, rolcreatedb, rolcreaterole, rolbypassrls from
   pg_roles where rolname = 'platform_app'` is all false.
3. **Run the migration job** ([deploy.md](deploy.md), step 1). The application role is then able to read what the migrations made
   (default privileges), and cannot read the migration history or change the schema.
4. Start the services. They check the schema first; an application that finds it behind refuses to start and says so.
5. **M002, once, as the owner, after the application has started once:** the first platform administrator
   (`db/manual/M002__grant_first_platform_administrator.sql`, the steps in `docs/production-transition-plan.md`, 4.4). Never a
   default account.

## The connection arithmetic

`demand = (tasks at their maximum x pool size) + jobs`, where the pool is 10 per task (`PLATFORM_DB_POOL_SIZE`), jobs are the
migration job (2), the owner's own sessions (5) and a margin of 20 percent of the pool total. **Rule: `demand <= 0.5 x max_connections`**
so that a failover (every connection reconnecting at once) stays inside the limit. `max_connections` is set by the service from the
instance's memory (roughly memory in bytes / 9,531,392, at most 5,000).

| | tasks at maximum | pool | jobs | demand | `max_connections` (about) | alarm |
|---|---|---|---|---|---|---|
| staging | 3 | 10 | 2 + 5 | 37 (45 with the margin) | 380 on a small class | 150 |
| production | 8 | 10 | 2 + 5 | 87 (105 with the margin) | decided with the instance class | 300 |

Recompute when `api_max_tasks`, the pool or the class changes, and set `db_connections_alarm` with it.

## A failover

The service promotes the standby; every connection drops and the address stays the same. Readiness turns false on tasks that cannot
reach the database, the pool opens new connections when it can, and nothing is restarted. Expect `database-events` and perhaps a
short burst of errors. Check: readiness true on every task, the pool recovered (no restart), a request works. The rehearsal proves
the recovery (`./db-interrupt.ps1`); the failover time of the real service is measured on staging.

## Restore

The rehearsal restores a dump into a clean database by script and times it (`./restore.ps1`). In the cloud a restore is a point-in-time
or snapshot restore into a **new** instance *(cloud)*: restore, point a staging copy at it, run the checks, only then switch (never
restore over the running database). Sprint 35 measures how long that takes and how much it loses; this page makes no claim.
A restored database needs the key-encryption key that wrapped its credentials ([kek-rotation.md](kek-rotation.md)).

## How I know it worked

`select platform_schema_version()` is the release's version; `platform_app` has no special privileges and cannot create a table;
readiness is true on every task; the alarms `database-*` are quiet.
