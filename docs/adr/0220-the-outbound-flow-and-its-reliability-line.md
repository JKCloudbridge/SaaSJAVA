# ADR-0220: The outbound flow and its reliability line (S28)

- **Status:** Accepted
- **Date:** 2026-10-30
- **Sprint:** S28
- **Related:** [ADR-0016](0016-transactional-outbox-and-idempotent-consumers.md), [ADR-0133](0133-event-processing-stays-on-the-outbox-decision-d3-final.md), [ADR-0210](0210-the-connection-lifecycle-and-connection-testing.md)

## Decision

`OutboundHandler` is an `EventHandler` on `record.created|updated|deleted`, like the workflow handler: nothing is added to the write path and it runs after the commit, in the outbox relay. For each **switched-on** connection that listens for the event on the object, has a **live** mapping and whose scope allows READ, it starts one `integration_delivery` (unique per connection, direction and event, so two instances start it once) and, after the handler's transaction commits, hands the send to `OutboundSender`. An organization with no listening connection costs one read of the connections.

`OutboundSender` prepares the request in a short transaction (the record as it is **now**, mapped under the version the delivery started with), makes the call with no transaction held, under one deadline, through `EgressClient`, and records the end in a short transaction. Nothing waits for an external system inside a person's save or an approval.

## The smallest honest line

**Promised:** one attempt for each event and connection, whichever instance handled it; the end recorded as `DONE`, `FAILED` or `SKIPPED` with a stable reason and, when there was one, the status; nothing lost without a trace; a bounded number of sends at once per instance (the rest are recorded as `busy`); records of finished deliveries kept for `deliveryRetention` (30 days) and purged a batch at a time; a list for the administrator (`GET .../deliveries`).

**Not promised, and not done:** retries, a queue, circuit breakers, rate limits per connection, dead letters and replay, ordering between events of one record (a later send reads the record as it is now, so the last state wins), and recovery of a send that was under way when an instance stopped: it stays `PENDING` and the list shows it as interrupted after `staleAfter`; it is **not** repeated because it may have reached the other side. These are Sprint 29, which takes over from the delivery row and the sender's single seam.

Only events after a mapping is live are sent; there is no bulk or historic sync (ADR-0224).
