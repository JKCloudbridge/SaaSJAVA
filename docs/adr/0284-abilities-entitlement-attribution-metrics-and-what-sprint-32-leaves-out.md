# ADR-0284: Abilities, entitlement, attribution, metrics, and what Sprint 32 leaves out (S32)

- **Status:** Accepted
- **Date:** 2026-11-23
- **Sprint:** S32
- **Related:** [ADR-0039](0039-abilities-profiles-access-policies-and-individual-grants.md), [ADR-0234](0234-the-attempt-log.md), [ADR-0272](0272-attribution-logs-and-metrics-of-a-connected-application.md), [ADR-0273](0273-abilities-entitlement-the-key-space-of-keyed-writes-and-what-sprint-31-leaves-out.md), [ADR-0274](0274-what-a-webhook-subscription-is-and-where-it-lives.md)

## Decision

1. **Four new abilities,** apart from `integrations.*`, `credentials.*` and `clients.*`: `webhooks.view` (list, read, the delivery
   log and the attempts; never a secret), `webhooks.manage` (make, change while off, test, switch on and off, replace the signing
   secret, remove; it needs `webhooks.view` too), `webhooks.replay` (send a dead letter again, one or all: it sends a record
   identifier to an outside address a second time, so it is apart from managing, as `integrations.replay` is), and `usage.view`
   (see the organization's limits and use, numbers only). A platform role has none of the webhook abilities and no door through
   support access.
2. **Three separate mechanisms, asked in this order and never merged:** the caller is an active member of the organization the
   host names, then the ability, then the plan feature. The feature is the existing **`integrations`** (webhooks are an
   integration; the catalogue does not separate them): with it off the endpoints answer `CONFLICT` in words, the event handler
   queues nothing and the worker holds what is due. The licence, the permission and the feature are three questions.
3. **A token of a connected application never reaches** the webhooks or the usage page (`ClientTokenAccessFilter` allows the
   public prefix and nothing else), so an application cannot make a webhook for itself or read the limits.
4. **Attribution, logs, metrics.** A delivery's log line and audit records carry the subscription identifier, the delivery
   identifier, the event type, the state and a stable reason, **never an address, a header, a response body or a secret.**
   Audit actions: `webhooks.created|updated|tested|enabled|disabled|removed|secret_rotated|replayed|auto_disabled`,
   `webhooks.delivery.dead_lettered`, `limits.override_set|override_removed`. Metrics by result, **never an identifier as a
   tag:** `platform.webhook.events{result}`, `.deliveries{result}`, `.attempts{result,reason}`, `.skipped{reason}`, `.superseded`,
   `.queue.depth`, `.queue.age`, `.disabled`, `.gate{result}`, `.circuit{state}`, `.send.duration{result}`, `.tests{result}`,
   `.secrets{result}`, `.replayed`, `.purged`, and for the limits `platform.limits.requests{result}` (allowed, limited),
   `platform.limits.quota.exhausted`, `platform.limits.errors`; no organization, application, member or webhook is a tag.
   `WebhookLogsAreCleanIT` and `RateLimitLogsAreCleanIT` hold this with canaries.
5. **The health view** gains a section (`WebhookHealth`): counts of what waits, what is set aside, how many subscriptions are
   failing or were switched off, how old the longest wait is; across organizations only what the worker last measured.

## What Sprint 32 leaves out, on purpose

| Left out | Why |
|---|---|
| User-delegated applications (authorization code, consent, refresh for public clients) | Sprint 31A, after the deployment sprint |
| Events beyond the record events (approvals, workflows, imports) | Later additions to the closed catalogue |
| Payloads with values or field names, a filter on field values, a per-field subscription | The message names a record and nothing else (ADR-0275) |
| A messaging or queue sink, a file sink, mutual TLS to the receiver | Not needed for V1; each is its own decision |
| A developer portal outside Setup | Setup is the one place |
| Billing by usage | The quotas protect; they do not charge |
| A plan-based scheme of numbers | The licensing module has no place for numbers; none was invented |
| A per-application override | Cheap to add; not needed yet (ADR-0283) |
| A service identity behind a subscription | ADR-0267 (the dedicated integration member practice, and the triggers to revisit) |
| Strict order of events between records | A message is a hint; the receiver fetches (ADR-0278) |

## Consequences

- Webhooks and limits close Milestone M8 with the doors the plan named, none left open, and the next sprint can be the
  deployment without a security-relevant TODO in this half.
