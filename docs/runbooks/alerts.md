# Runbook: alerts

Sprint 33 (decisions in [ADR-0294](../adr/0294-operating-it-alerts-logs-and-backups.md)). The alarms are made from one file,
`deploy/alerts/alerts.json`; this page has a row and a section for every entry (a test holds the two together). **Page** means the
person on call is told at once; **ticket** means it is looked at the same working day. Alarms go to the environment's notification
topic, to the owner's address first (the subscription has to be confirmed from the mail the topic sends).

## When to use this

When an alarm arrives, when you are asked "is anything wrong?", and when you add a background job or a queue (it brings its entry in
the file and its section here). Each section says what the alarm means and what to do first. Commands marked *(cloud)* are run by
the owner against the real environment; the others were run against the rehearsal.

## The table

| Alert | Who | What it means | Section |
|-------|-----|---------------|---------|
| `api-unhealthy` | page | No application task passes its readiness check | [below](#api-unhealthy) |
| `web-unhealthy` | page | No web task passes its health check | [below](#web-unhealthy) |
| `api-tasks-low` | page | Fewer application tasks run than the service needs (crash loop, failed release, no capacity) | [below](#api-tasks-low) |
| `http-5xx` | page | The application answers with server errors | [below](#http-5xx) |
| `balancer-5xx` | page | The load balancer itself answers with errors (a dropped request) | [below](#balancer-5xx) |
| `latency-p95` | ticket | The 95th percentile of the response time is over two seconds | [below](#latency-p95) |
| `database-cpu` | ticket | The database is busy | [below](#database-cpu) |
| `database-connections` | ticket | The database is near its connection limit | [below](#database-connections) |
| `database-storage` | page | Less than 10 GiB of database storage is free | [below](#database-storage) |
| `cache-unreachable` | ticket | Redis is not reachable; the limits degrade to per instance | [below](#cache-unreachable) |
| `cache-cpu` | ticket | The cache is busy | [below](#cache-cpu) |
| `outbox-dead` | ticket | Events were set aside as dead letters | [below](#outbox-dead) |
| `mail-dead` | ticket | Mails were given up on | [below](#mail-dead) |
| `workflow-queue` | ticket | More than 1000 workflow executions are waiting | [below](#workflow-queue) |
| `workflow-dead-letters` | ticket | Workflow executions are dead letters | [below](#workflow-dead-letters) |
| `integration-queue` | ticket | More than 1000 deliveries to external systems are waiting | [below](#integration-queue) |
| `integration-dead` | ticket | Deliveries to an external system were set aside | [below](#integration-dead) |
| `webhook-queue-age` | ticket | The oldest due webhook delivery is more than ten minutes old | [below](#webhook-queue-age) |
| `webhook-disabled` | ticket | The platform switched a webhook subscription off | [below](#webhook-disabled) |
| `certificate-expiry` | page | Fewer than 21 days to the end of the wildcard certificate | [below](#certificate-expiry) |
| `database-events` | page | A backup failed, or the database failed over or ran low on storage | [below](#database-events) |
| `budget` | ticket | Spending passed 80 percent of the monthly budget, or is forecast to pass it | [below](#budget) |

## The sections

### api-unhealthy

Every request of every organization fails at the balancer. 1. Look at the service's events *(cloud)*: are tasks starting and stopping?
2. Read the latest lines of the application's log group: a start refused by the schema check ("the database schema is behind this
release") means the migration job did not run before the release ([deploy.md](deploy.md), then [rollback.md](rollback.md)); a database
or cache connection error means [database.md](database.md). 3. If a release just went out and the circuit breaker did not roll it
back, roll back ([rollback.md](rollback.md)). 4. If the database is the cause, nothing restarts the application: it recovers by itself.

### web-unhealthy

The pages cannot load; the API may still answer. Look at the web service's events and its log group. A release of the web image
that does not become healthy is rolled back by the service; if not, redeploy the previous web tag ([rollback.md](rollback.md)).

### api-tasks-low

Tasks stop as fast as they start, or cannot be placed. Look at why the last tasks stopped *(cloud)*: out of memory (the stop reason
says so; raise the memory in the values file), a failed health check, a refused start (see `api-unhealthy`), or no capacity in the
region. A task that is killed by the stop timeout instead of ending by itself appears in the log as the worker "cut off" line:
look at what it was doing.

### http-5xx

More than 25 server errors in five minutes. 1. Is a release in progress? If yes, stop it and roll back ([rollback.md](rollback.md)).
2. Group the errors by the request identifier in the log (one request identifier is one request; the trace identifier follows it).
3. A database or cache outage shows here too ([database.md](database.md)). 4. The errors never contain a value; the log lines name
the class of the failure.

### balancer-5xx

The balancer could not reach a task: connection refused or timed out. This is the signature of a release that drops requests
(the shutdown delay, the deregistration delay or the stop timeout are wrong: [deploy.md](deploy.md), "the numbers") or of tasks that
died. Check the service's events and the drain numbers in the values file against `DrainNumbersTest`.

### latency-p95

Slow answers for fifteen minutes. Look at the database (CPU, connections, a slow statement in its log), at the cache, and at one
organization flooding the system (the limits alarms and the usage page of that organization, [rate-limits.md](rate-limits.md)).
Scale the service out only if the application's CPU is the limit ([scaling.md](scaling.md)).

### database-cpu

CPU over 80 percent for fifteen minutes. Find the statements with the database's performance view *(cloud)*; a missing index is
a migration (expand only: index concurrently). If it is steady load, the instance class is too small: the decision follows the
storage benchmark ([database.md](database.md)).

### database-connections

Open connections are above the share of the server's limit that the values file allows. The demand is `tasks x pool + jobs`
([database.md](database.md), the arithmetic). Check that the number of tasks did not grow past its planned maximum, that no job
holds connections open, and that the pool size was not raised. Raise the instance class or lower the pool; never raise `max_connections`
without the arithmetic.

### database-storage

Less than 10 GiB free. The storage grows by itself up to its limit; look at which tables grew (the audit trail, outbox, deliveries)
and at the retention settings; raise the limit in the values file; this is a page because a full disk stops the database.

### cache-unreachable

The application logged "Redis is not reachable": the sign-in and API limits now count per instance (degraded, not down) and a sign-in
lock still holds (it is in the database). 1. Check the cache's state and its security group *(cloud)*. 2. The application recovers by
itself a few seconds after the cache returns (one warning is logged when it starts and one when it ends). 3. If it lasts, the limits
are weaker than designed for as long as it lasts: treat it as a ticket now, a page if an abuse is going on.

### cache-cpu

CPU of the cache over 70 percent for fifteen minutes: the limits' counters are the only load, so look for a flood of requests first.

### outbox-dead

The outbox gave up on an event. The event is kept: the parked-events screen of the organization lists it and an administrator can put
it back ([automation.md](automation.md)). Find the cause (a handler failing for a stable reason) in the log by the event type; the
log names the class of the failure and never the payload.

### mail-dead

A mail used all its attempts or became too old (its link would be stale). Look at the mail provider's status and at the SMTP
credentials in the secret store; the mail screen of the platform console lists what was given up on. Sign-up, reset and invitation
mails are the front door: a page if it lasts.

### workflow-queue

More than 1000 workflow executions waited for fifteen minutes: the dispatcher is stopped (`PLATFORM_WORKFLOW_ENABLED`), slow, or one
organization is flooding it (the fair share limits what one organization takes, [automation.md](automation.md)). Check the
automation health view of the platform console.

### workflow-dead-letters

An execution used its attempts. The workflow runs screen of the organization lists it and an administrator can replay it
([automation.md](automation.md)). A rising count after a release points at the release.

### integration-queue

More than 1000 deliveries wait: an external system is down (its circuit is open, which is the design) or the limits are too low.
The connection's monitoring screen shows the circuit and the queue; nothing is lost while it waits, and a connection that is off
holds its queue ([integrations.md](integrations.md)).

### integration-dead

Deliveries were set aside after their attempts or failed permanently. An administrator replays them from the connection's monitoring
screen once the cause is fixed ([integrations.md](integrations.md)).

### webhook-queue-age

The oldest due webhook delivery is over ten minutes old: the sender is stopped (`PLATFORM_WEBHOOKS_ENABLED` is false on the instances?)
or a receiver is slow ([webhooks.md](webhooks.md)).

### webhook-disabled

A receiver failed without one success for the period the settings state; the platform switched its subscription off and told the
administrators. Nothing to repair here; tell the customer if they have not noticed ([webhooks.md](webhooks.md)).

### certificate-expiry

Fewer than 21 days left. The provider renews the certificate by itself; if this fires, the renewal is failing, usually because the
validation record in DNS was removed or the zone was moved. Restore the validation record *(cloud)*; the certificate's page says what
it is waiting for.

### database-events

Sent by the database service: a backup failed, a failover happened, storage is low. For a failed backup, start a manual snapshot
*(cloud)* and look at why; for a failover, expect connections to drop and the pool to recover by itself ([database.md](database.md));
confirm readiness is true on every task afterwards.

### budget

Spending passed 80 percent of the monthly budget or is forecast to pass it. Look at the cost view by service *(cloud)*; the usual
causes are a second NAT gateway, data transfer, log volume, or an environment left running that should have been destroyed (staging).

## How I know it worked

The alarm returns to OK by itself (its notification says so) and, for a page, the check that raised it passes again: readiness
true on every task, a request through the balancer answers, the queue falls, the certificate's days rise. Write what happened in
the incident notes ([incident.md](incident.md)).
