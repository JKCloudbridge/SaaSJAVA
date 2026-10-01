# ADR-0016: Transactional outbox, polling relay and idempotent consumers (decision D3)

- **Status:** Accepted for the outbox and the relay (D3, first step). The event processor and the broker question close in
  Sprint 22.
- **Date:** 2026-10-01
- **Decision IDs:** D3
- **Sprint:** S2

## Context

Modules react to each other's changes (audit, workflow, notifications, integration) and must never lose or duplicate the
effect: "the record was saved but the event was not published" and "the event was handled twice" are both unacceptable.
Publishing to a separate system after the commit cannot be made atomic with the commit. Decision D3 chose PostgreSQL itself
as the first transport: the event is a row written in the same transaction as the change, and a worker delivers it.

## Decision

1. **The outbox** is the table `outbox_event` (V004): tenant-scoped (ADR-0015), with the tenant, user and membership of the
   change, a type (`tenant.suspended`), a JSON payload, a status (`PENDING`, `DELIVERED`, `DEAD`), the attempt count, the
   time the next attempt is due, a lease (`locked_until`), and the type name of the last failure (never its message: library
   messages can quote the data that failed). `EventPublisher.publish` writes the row **in the caller's transaction**
   and refuses to run outside a transaction or outside a tenant context. An event exists if and only if its change
   committed; a rollback discards both; a crash after the commit cannot lose it. Every event belongs to exactly one tenant,
   taken from the context, never from an argument.
2. **Contracts live in the shared kernel** (`EventPublisher`, `NewEvent`, `EventHandler`, `EventEnvelope`); the implementation
   is the infrastructure module `outbox` (`app.platform.outbox`, allowed dependencies: `sharedkernel`, `tenant`,
   `observability`). Business modules announce through the interface and react by exposing `EventHandler` beans, so **no
   module needs an edge to `outbox`** (an architecture test forbids it) and the module graph of `modules.md` stays as it was.
3. **The relay** (`OutboxRelay`, a lifecycle component on every instance, switch `PLATFORM_OUTBOX_ENABLED`) polls, pausing
   `pollInterval` (default 1 second) when idle. A poll **claims** a batch with one statement: rows that are `PENDING`, due and
   not leased, oldest first, `FOR UPDATE SKIP LOCKED`, setting a **lease** (default 2 minutes), adding one to `attempts` and
   bumping the version. Several instances polling at once never wait for each other and never pick the same row. The version
   after the claim is the claim's token: recording the outcome succeeds only while the version is unchanged, so an instance
   that was too slow and lost its lease cannot overwrite the outcome of the one that took over.
4. **Crash safety.** A claim that is never completed expires with its lease and the event is claimed again by any instance.
   The attempt is counted **at the claim**, so an event whose handling keeps killing the process reaches the attempt limit
   and becomes a dead letter without being handled again, instead of crashing instances forever.
5. **Idempotent consumers.** Delivery is at least once; handling is effectively once. For each handler the relay opens one
   transaction under the event's tenant context and first inserts a row into `processed_event` (consumer name, event ID;
   unique) with `on conflict do nothing`; only when the row was new does the handler run, in the same transaction. The marker
   and the handler's own changes commit together or not at all. A second delivery (a crash between the commit and the
   report, a lease takeover) finds the marker and skips the handler. When two instances handle one event at the same
   moment, the second insert waits for the first transaction and then either finds the marker or, if the first rolled back,
   proceeds. Several handlers of one event are independent: one failing does not re-run the ones that succeeded.
   **Only database changes are protected.** A handler that calls something outside the database must pass the event ID as the
   idempotency key of that call (stated in the `EventHandler` contract).
6. **Tenant context restored.** While a handler runs, the event's tenant (and user and membership) is the current context
   and in the database session, so the handler sees only that tenant's rows and its logs carry the tenant ID. Nothing is left
   on the polling thread.
7. **Retries and dead letters.** A failing handling rolls back that handler's changes, records the failure's type and
   schedules the next attempt with exponential backoff (default 5 seconds doubling, at most 15 minutes) and 20 percent random
   jitter so that events that failed together do not retry together. After `maxAttempts` (default 8) the event becomes
   `DEAD`: it stays in the table for a person to look at (a review and redrive tool comes with Sprint 23), is reported
   through the error tracking hook with the tenant and type, and is counted (`platform.outbox.events{result}`).
8. **Retention.** Delivered events are removed after 7 days, idempotency markers after 30 days (must exceed the delivered
   retention and the longest retry window, or a very late redelivery would run twice); dead letters are never removed
   automatically.
9. **Events nobody listens to are delivered.** A subscriber added later (the audit module, Sprint 9) does not see events from
   before; events older than the retention are gone.
10. **Cross-tenant access** of the relay uses the system scope `OUTBOX_RELAY` (ADR-0015), nothing else.

## What was verified (integration tests on a real PostgreSQL)

Committed with its change and not before the relay runs; a rolled-back change leaves no event and no handling; handlers run
with the right tenant, user, membership, database session and row isolation; retry with a growing delay and the change applied
once; dead letter after the limit with no message, no later redelivery, one error report and no secret in the logs;
handlers that already succeeded are not re-run; an instance dying after the claim, and one dying after the handler committed
but before reporting; an event that keeps killing instances is dead-lettered unhandled; a slow instance cannot overwrite the
outcome of the one that took over; five instances polling together handle 180 events across three tenants exactly once each
(`OutboxIT`); the relay starts and stops with the application and handles the real lifecycle events of tenants
(`OutboxPollingIT`); retention keeps recent rows and dead letters; the relay scope's rights at the database (`SystemScopeIT`).
Temporarily making the marker insert always succeed failed the two tests about double handling, then was restored.

## Not verified, known limits

- No load test: throughput is bounded by one poll per instance per interval and a batch of 10 handled one after the other; a
  deployment that needs more raises the batch size or adds instances (to be benchmarked in Sprint 14 and revisited in
  Sprint 22).
- **Latency** is the poll interval plus handling time. A commit-time wake-up of the local instance was not built.
- **Order is not guaranteed**, especially across retries; handlers must tolerate that.
- Observability: counters exist; dashboards and alerts on dead letters and backlog age come with Sprints 25 and 36.
- The lease (default 2 minutes) must exceed the time a batch takes; if it does not, another instance takes the rest over,
  which is safe but wasteful.

## Consequences

- Reliable reactions between modules without a broker, using the database the platform already operates.
- The outbox table grows by the event rate times the retention; the partial indexes keep the relay's query small.
- A later broker (Sprint 22) can replace the relay behind the same publisher and handler contracts.

## Alternatives considered

- **Publishing to a broker after the commit:** not atomic with the commit; the failure window is the problem.
- **`LISTEN`/`NOTIFY` for wake-up:** lost when nobody listens and not durable; polling is the durable base, a notification
  could be an optimization later.
- **Advisory locks or a single elected leader:** more moving parts than row locks with skip-locked, and a leader is a
  bottleneck and a failure mode.
- **Holding the row lock for the whole handling:** simplest, but ties a connection and a lock to slow handlers and cannot
  count a crashed attempt.
- **Exactly-once delivery:** not achievable across a crash; exactly-once *effect* with idempotent handlers is.

## References

- `platform-shared-kernel/src/main/java/app/platform/sharedkernel/events/`
- `platform-app/src/main/java/app/platform/outbox/`, `platform-app/src/main/resources/db/migration/V004__create_outbox_tables.sql`
- `platform-app/src/test/java/app/platform/outbox/internal/` (`OutboxIT`, `OutboxPollingIT`, `BackoffTest`,
  `OutboxConfigurationTest`)
- Architecture notes: transactional outbox (phase 4), events and the synchronous versus asynchronous rule (notes 1 and 6).
