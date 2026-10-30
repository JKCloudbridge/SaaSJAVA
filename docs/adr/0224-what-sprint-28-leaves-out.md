# ADR-0224: What Sprint 28 leaves out, and why (S28)

- **Status:** Accepted
- **Date:** 2026-10-30
- **Sprint:** S28
- **Related:** [ADR-0211](0211-what-sprint-26-leaves-out.md), [ADR-0215](0215-what-sprint-27-leaves-out.md)

Written down so that nobody reads an omission as an oversight.

| Left out | Why | Where it comes |
|----------|-----|----------------|
| **Retries, a queue, circuit breakers, per-connection rate limits, dead letters, replay, recovery of an interrupted send** | one honest attempt with a visible result is the line (ADR-0220) | Sprint 29 |
| **Ordering between events of one record** | a send reads the record as it is now, so the last state wins; strict order needs the queue | Sprint 29 |
| **Other protocols and their adapters** | the interface was extended only as far as the sample type needs it | Sprint 29 |
| **Monitoring screens for the flows** | the deliveries list and the metrics exist; a dashboard needs the queue's numbers | Sprint 29 |
| **Signing the platform's own outgoing webhooks, webhook secrets** | their own lifecycle (hashing, one-time display) | Sprint 32 |
| **The public API and machine clients** | a separate surface with its own tokens | Sprint 30 |
| **Bulk or historic sync of existing records** | only events after a mapping is live are sent; a "send existing" action is a bulk job with its own limits and idempotency | on request |
| **Conflict resolution beyond the three rules, a per-field choice** | one rule per mapping is enough to start and is stated in the screen | on request |
| **Mutual TLS or a bearer-only scheme for the receiver** | a signature over the body is stronger and needs no certificate handling | not planned |
| **Cross-instance rate limit of the receiver** | a per-source in-memory allowance bounds an anonymous caller; the per-connection limit belongs with the others | Sprint 29 |
| **Nested payloads, lists and relations in the canonical model** | the platform's fields are flat (ADR-0216) | not planned |
| **A mapping marketplace or a named external product** | the core never names an external system | never |

A need that fits one of these rows cannot be expressed today; it is never half-supported.
