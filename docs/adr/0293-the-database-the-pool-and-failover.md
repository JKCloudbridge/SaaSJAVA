# ADR-0293: The database, the pool, and failover (S33)

- **Status:** Accepted
- **Date:** 2026-11-24
- **Sprint:** S33
- **Related:** [ADR-0003](0003-rls-defence-in-depth.md), [ADR-0009](0009-migration-framework-and-database-roles.md), [ADR-0083](0083-storage-benchmark-result-gate-g1.md), [ADR-0108](0108-limits-per-organization.md), [ADR-0288](0288-migrations-as-a-job-the-compatibility-rule-and-rollback.md)

## Decision

1. **PostgreSQL 18, multi-zone, point-in-time recovery, TLS required.** Production has a standby in the other zone with automatic
   failover, automated backups kept for fourteen days and point-in-time recovery; staging may run single-zone. Every connection is
   encrypted (`rds.force_ssl`; `sslmode=require` in `PLATFORM_DB_URL`). The server's log keeps no parameter values next to a slow
   statement or an error (`log_parameter_max_length` 0); a constraint error still quotes the value in its own text, so the database
   log group is restricted and short-lived (the incident runbook says who may read it).
2. **Two roles.** The owner (the database service's master user) runs the migrations (the job), M001 and M002; the application role
   `platform_app` has no schema rights, cannot read the migration history and is told so by the tests (`SchemaCheckIT`). The
   owner's password never reaches the application.
3. **The connection arithmetic.** A database allows `max_connections` (the service sets it from the instance's memory, roughly
   memory in bytes / 9,531,392, at most 5,000). The application's demand is:
   `demand = (application tasks at their maximum x PLATFORM_DB_POOL_SIZE) + jobs`, with the pool 10 per instance (an organization
   at its limit leaves connections for the others: [ADR-0108](0108-limits-per-organization.md)) and jobs = the migration job (2) plus
   the owner's own sessions (5) plus a margin of 20 percent of the pool total. **The rule: `demand <= 0.5 x max_connections`**, so a
   failover (every connection reconnecting at once, doubling the demand for a moment) stays inside the server's limit. Staging:
   3 x 10 + 2 + 5 = 37 against a small class's ~380 (alarm at 150). Production: 8 x 10 + 2 + 5 = 87 (+20 percent = 105) against a
   class chosen after the benchmark (alarm at 300, set with the class); **the numbers are recomputed whenever the maximum number
   of tasks or the pool changes**, in `runbooks/database.md`.
4. **A failover drops the connections; the pool recovers without a restart.** Readiness includes the database, so an instance
   that cannot reach it says "not ready" and leaves rotation; the pool fails fast (five seconds) and opens new connections when the
   database is back. `db-interrupt.ps1` proves it on the rehearsal: the database is stopped for twelve seconds in the middle of load;
   readiness turns false, no instance restarts, readiness is true again within the limit, and a new load runs without a failed
   request. The failover time of the real service is measured on staging (a manual step) and by Sprint 35; no number is claimed here.
5. **Instance class is the owner's decision after the storage benchmark** (gate G1, [ADR-0083](0083-storage-benchmark-result-gate-g1.md), condition 6): the
   benchmark is run on each candidate and the smallest that passes is chosen. The values file says so with a marker.
6. **The cache** is a replication group with a password and encryption in transit; the platform targets Redis 8 and uses only basic
   counters and expiry, so an older engine line may do, to be confirmed with the rehearsal's checks (it runs 8). A cache outage
   degrades the limits to per instance and nothing else ([ADR-0021](0021-brute-force-protection-and-rate-limits.md), [ADR-0281](0281-the-counters-redis-and-failing-open.md)).

## Consequences

- Backups are switched on in the files and a restore is rehearsed by script (`restore.ps1`); the owner's cloud restore drill and the
  measurement of recovery time and data loss are Sprint 35's and are not claimed here.
