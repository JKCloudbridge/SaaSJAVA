# ADR-0278: Deliveries, attempts, reasons, order, replay and retention (S32)

- **Status:** Accepted
- **Date:** 2026-11-23
- **Sprint:** S32
- **Related:** [ADR-0230](0230-the-queue-ordering-and-its-bound.md), [ADR-0231](0231-claiming-leases-fair-share-dead-letters-and-replay.md), [ADR-0234](0234-the-attempt-log.md), [ADR-0274](0274-what-a-webhook-subscription-is-and-where-it-lives.md), [ADR-0277](0277-signing-secrets-rotation-and-visibility-at-delivery.md)

## Decision

1. **A handler of an event only writes a row.** `WebhookEventHandler` (an `EventHandler` of the outbox on the four record events)
   writes one `webhook_delivery` for every switched-on subscription that asks for the event and is not limited to other objects,
   and returns. It never calls out, never judges who may see the record (that is asked at delivery, ADR-0277) and adds nothing to
   the write path. A worker, on any instance, makes the call later, **outside any database transaction**. The kill switch
   `PLATFORM_WEBHOOKS_ENABLED` makes the handler queue nothing and the worker send nothing; the screens still work.
2. **At-least-once, and one event makes one delivery.** The outbox can hand an event over twice and a retry repeats a delivery.
   A delivery has a stable identifier, sent as `Webhook-Id` every time, and a unique key at the database (subscription, event)
   so a repeated outbox event makes one delivery. The documentation says plainly that **a receiver must deduplicate by that
   identifier.** A replay is a new generation of the same delivery and carries the same identifier.
3. **Order.** Latest state wins for updates: an update that is still only waiting is replaced (`SUPERSEDED`) by a newer update
   of the same record, because a delivery names a record and never a value, so an older one says nothing the newer does not.
   There is **no promise of order** between records or between a create, an update and a removal, and no strict-order claim
   query: a message is a hint, and the receiver fetches the current record. (This is simpler than Sprint 29's strict order for
   creates and removals because nothing in a message depends on the one before it.)
4. **The delivery and its attempts hold facts and no body:** identifiers, a state, a stable reason, a class of status, times and
   counts; **never a header value, a response body, an address or a typed word.** The address appears only on the subscription's
   own screen. The database checks the shape of a reason (`^[a-z_]{1,40}$`). The reasons come from three closed lists that
   already have words (`WebhookReason`, `FlowReason`, `TestReason`); the API gives the platform's own sentence next to the key
   (`WebhookReasons`), so the browser holds no list.
5. **States:** `PENDING`, `SENDING`, `DONE`, `SKIPPED`, `SUPERSEDED`, `DEAD_LETTERED`, held by `DeliveryState` and the database guard
   (a finished delivery is final; a dead letter comes back only as a replay that starts the next generation with the attempts
   counted afresh; attempts are never taken back; what a delivery is about never changes). An **attempt** is one immutable row.
6. **Replay.** An administrator with `webhooks.replay` sends one dead letter again, or every dead letter of a subscription
   (oldest first, at most 500 a call, and the answer says whether more remain). Two clicks make one: the second finds it no
   longer dead. A replay sends an identifier to an outside address a second time, which is why it is an ability of its own.
7. **A queue bound.** At most 10,000 open deliveries for one subscription; an event that arrives over it is recorded as a
   visible dead letter (`queue_full`), never dropped. A switched-off subscription holds its queue, which is bounded, and a removed
   one sets what was waiting aside (`subscription_removed`).
8. **Retention.** A delivery that ended and its attempts are removed after `platform.webhooks.retention.deliveries` (30 days, never
   fewer than one: the database refuses to delete a delivery that is open or younger than a day). The clean-up is bounded (a
   batch of rows) and idempotent, runs inside the organization whose delivery has just ended (so it needs no cross-organization
   scope), and runs on about one success in fifty.

## Consequences

- The log is safe to show to an administrator and to read in an incident: it states what happened and never what was said.
- A receiver that wants the order of changes of one record reads the version it fetches.
