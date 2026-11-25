# Runbook: scaling

Sprint 33. Rehearsed by `./scale.ps1` (1, 2 and 3 replicas under load).

## When to use this

To add or remove application tasks, to understand what must be true for several instances to work together, or when CPU or latency
alarms ask for more capacity.

## What several instances share, and how

- **Sessions and tokens:** in the database (a sign-in's steps may each reach another instance); the signing keys are the same files
  on every instance.
- **The security cache** is per instance and keyed by a version kept in the database, so the next question after a change is right
  everywhere (`SecurityCacheIT`, three real instances).
- **Rate limits:** counters in Redis shared by every instance (`PLATFORM_REDIS_*`). Without Redis each instance counts for itself, so
  with several instances the limits are looser than designed: a degraded state with an alarm (`cache-unreachable`), never an outage.
- **Background work** (the outbox, mail, workflows, approval timers, outbound deliveries, webhooks, retention) uses leases and
  one-winner claims, so it is safe on any number of instances; the `..._ENABLED` switches exist to run a job on fewer.
- **Files** go to the object store, not a local disk.

## Steps

1. Raise `api_min_tasks`/`api_max_tasks` in the values file (the service also scales on CPU within them). Check the **connection
   arithmetic** ([database.md](database.md)) first: more tasks means more connections.
2. Plan, read, apply. The service adds tasks beside the running ones; no request is affected.
3. Lowering is the same; a stopping task drains ([deploy.md](deploy.md)).

## What the rehearsal checks, and what the tests check

The rehearsal: no failed request at 1, 2 and 3 replicas; every replica serves; sign-ins work across replicas; the sign-in limit is
shared (about the same number of attempts get through at 3 replicas as at 1). The integration tests that start several real
instances: the security cache, the outbox, the workflow dispatcher, the approval timers, a worker that crashes or stops (`OrderlyStopIT`,
`WorkerCrashIT`, `FairShareIT`).

## How I know it worked

All tasks are healthy; the alarms are quiet; the database connections stay under the alarm; each task shows requests in its
metrics.
