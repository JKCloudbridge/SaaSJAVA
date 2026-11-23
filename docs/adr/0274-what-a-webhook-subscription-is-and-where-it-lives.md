# ADR-0274: What a webhook subscription is and where it lives (S32)

- **Status:** Accepted
- **Date:** 2026-11-23
- **Sprint:** S32
- **Related:** [ADR-0003](0003-rls-defence-in-depth.md), [ADR-0207](0207-the-connector-abstraction-and-the-adapter-spi.md), [ADR-0231](0231-claiming-leases-fair-share-dead-letters-and-replay.md), [ADR-0267](0267-a-connected-application-acts-as-the-member-who-registers-it.md)

## Context

After Sprint 31 an organization's software can call the public door with its own identity, but it can only ask. To learn that a
record changed it must poll, which wastes both sides' capacity. Sprint 32 lets an organization subscribe to events and be told.

## Decision

1. **A subscription is a standing instruction of an organization:** "when these things happen, tell this https address".
   It is a tenant-scoped row, `webhook_subscription` (V096): base columns, forced row level security, the tenant guard, a
   tenant-first index, an entry in `TenantScopedTables`, the schema scanner and a guard test as the application role and as the
   owner. It holds a name, the address, the events it asks for (a closed catalogue, ADR-0275), an optional list of the only
   objects it tells about (it can only narrow), the member it acts as, a state, the fingerprint of its definition, the number
   of its signing secret, and the circuit breaker and token bucket of the address.
2. **It acts as the member who makes it, for ever** (the model of ADR-0267, for the same reasons). The member and the membership
   are immutable at the database. There is no way to name another member and none to change it, so authority never grows; a
   subscription for another member is a new subscription. The practice is a dedicated integration member. What a subscription
   may tell is decided at the moment of each delivery by that member's rights (ADR-0277), and when the member is no longer an
   active member the subscription is **stopped**: it is shown as such, nothing is sent, and what was due is set aside by a stated
   rule (ADR-0278). A member who becomes active again makes it run again; nothing needs switching.
3. **It lives in the `integration` module**, which owns the delivery machinery and is the only module allowed to open a
   socket, so no new module edge is needed. A webhook is **not** a connector: it is not forced into `integration_connection`,
   whose lifecycle, settings and mapping do not apply. It has its own sub-area (classes named `Webhook*`), its own tables and its
   own lifecycle (ADR-0276).
4. **New tables beside the integration ones, with the pure classes shared**, rather than widening `integration_delivery`:
   `webhook_delivery` and `webhook_attempt` carry the states and guards of Sprint 29, but a delivery names a subscription and an
   event instead of a connection and a mapping, has no link to an external identifier, and holds no record value. The classes
   that are pure are the same ones: `RetryPolicy`, `CircuitBreaker`, `RateLimiter`, `DeliveryState` (ADR-0279). Widening the
   existing tables would have loosened their guards for both uses.
5. **At most `platform.webhooks.max-subscriptions` (50) a subscription per organization.**

## Consequences

- One more kind of tenant row with a guard, and no new module edge (`WebhooksArchitectureTest` holds the allowed dependencies of
  `integration`, `publicapi` and `platformadmin` as they were).
- The Sprint 29 reliability tests stay as they are, because the shared classes are untouched.
