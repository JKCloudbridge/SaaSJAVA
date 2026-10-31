# ADR-0230: The queue of deliveries, the order within a record and the bound (S29)

- **Status:** Accepted
- **Date:** 2026-10-31
- **Sprint:** S29
- **Related:** [ADR-0016](0016-transactional-outbox-and-idempotent-consumers.md), [ADR-0220](0220-the-outbound-flow-and-its-reliability-line.md)

## Decision

1. **A delivery is a unit of queued work.** The handler of a record event only writes the row (`PENDING`, due now); the
   sending is done by the worker (ADR-0231), so a send never depends on the thread that started it, and a restart loses
   nothing. States: `PENDING` (waiting, with a due time), `SENDING` (held by a worker with a lease), `WAITING_REMOTE`
   (ADR-0233), `DONE`, `SKIPPED`, `SUPERSEDED`, `DEAD_LETTERED`; `FAILED` stays for a refused receive and the rows of
   Sprint 28. `DeliveryState` is the one table; the database guard (V088) holds the same moves.
2. **Order within one record: the latest state wins for updates, strict for the rest.** A new update or removal replaces
   the older *update* deliveries of the same record that are still only waiting (`SUPERSEDED`, reason `superseded`): a send
   reads the record as it is when it is made, so the older one says nothing the newer does not. A delivery is claimed only
   when no earlier open delivery of the same record exists, so a waiting create blocks the update and the removal that
   follow it, and nothing overtakes anything.
3. **The bound is 10,000 open deliveries per connection** (`platform.integration.reliability.queue.max-per-connection`).
   A change that arrives when the queue is full is **recorded as a dead letter with the reason `queue_full`** (visible,
   counted, replayable), never dropped silently and never refused to the person whose save caused it.
4. **A connection that is switched off holds its queue**: its deliveries wait with the reason `connection_off` and go on
   when it is switched on again. Nothing is skipped because a connection was off for a while.
5. **No data loss means:** every event that was subscribed to ends in a delivery row, and a row ends in `DONE`, `SKIPPED`,
   `SUPERSEDED` or `DEAD_LETTERED`, or still waits. A record is never changed or left in doubt because a send failed.

## Consequences

The claim query carries the order rule (a `not exists` over the partial index `integration_delivery_record_open`), the
bound is counted with a limit, and both are measured in `OutageRecoveryIT` and `ReliabilityGuardIT`.
