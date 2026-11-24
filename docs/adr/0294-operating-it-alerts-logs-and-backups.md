# ADR-0294: Operating it: alerts, logs, backups and the first page of an incident (S33)

- **Status:** Accepted
- **Date:** 2026-11-24
- **Sprint:** S33
- **Related:** [ADR-0012](0012-observability.md), [ADR-0281](0281-the-counters-redis-and-failing-open.md), [ADR-0290](0290-secrets-keys-and-the-object-store-role-in-a-deployment.md), [ADR-0293](0293-the-database-the-pool-and-failover.md)

## Decision

1. **Alerts are one file** (`deploy/alerts/alerts.json`). The infrastructure code makes an alarm from each entry; the runbook
   `alerts.md` has a section and a table row for each (a unit test, `AlertsFileTest`, fails on an alert without an explanation or an
   explanation without an alert). Each names who is told (**page** at once, **ticket** the same working day), what it means and the
   first step. They are told to the owner's address by e-mail first (the subscription must be confirmed); decision 8 of the
   transition plan (an error-tracking tool) stays open and can be taken before staging, and the code's error-tracking hook is
   unchanged.
2. **The set:** no healthy application task, no healthy web task, fewer application tasks than needed (this also catches a crash
   loop), 5xx from the application, errors made by the balancer itself (the signature of a dropped request), slow answers (p95),
   the database's CPU, connections and free storage, the cache's CPU, Redis not reachable (from the log line, the sign-in and API
   limits degrade), outbox dead letters, mail dead letters, workflow queue depth and dead letters, integration queue depth and dead
   letters, webhook queue age and an auto-disabled subscription, the certificate's days to expiry, and, from the services' own
   events, a failed backup, a failover, low storage, and the budget. Where the platform has no gauge (an outbox lag) the alert is on
   what exists (dead letters, workflow event delay) and the runbook says so.
3. **Application metrics** are read from the management port inside each task by a collector and written to the metric store; the
   names are those of the exposition format and the first run on a real environment confirms them (a manual step). No metric tag is
   an organization, a member or a record ([ADR-0012](0012-observability.md), unchanged).
4. **Logs** go to the provider's log service as the structured lines the application already writes (request, trace and tenant
   identifiers in the context; no secret, value or personal data: the existing log tests hold). No image sets a debug level; trace
   shipping stays off by default (`PLATFORM_TRACE_EXPORT`); retention is 14 days in staging and 90 in production.
5. **Backups are switched on here and measured in Sprint 35.** Automated backups and point-in-time recovery in the files, the
   files bucket versioned (earlier versions kept 30 days), a restore rehearsed by script on the rehearsal database. **Nothing here
   claims a recovery time or a recovery point.**
6. **The first page of an incident** (`runbooks/incident.md`): "what happened to organization X's request twenty minutes ago": the
   request identifier a person sees, the log fields (request, trace, tenant), the trace, the audit trail of the organization, and
   the support-access rule (a platform person reads an organization's data only through `SupportAccess.require`, and the grant is
   in the audit trail).

## Consequences

- A new background job or queue brings its alert in the same change (an entry in the file and a section in the runbook).
- Multi-factor sign-in for platform roles stays a launch gate delivered by Sprint 34; `go-live` says a deployment without it is not public.
