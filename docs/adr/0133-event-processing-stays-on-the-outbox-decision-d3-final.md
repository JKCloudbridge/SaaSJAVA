# ADR-0133: Event processing stays on the outbox and the polling relay (decision D3, final)

- **Status:** Accepted for the design and the thresholds. **The measurement has not been run yet**: the harness is written and the
  thresholds below were fixed before any number exists; the result is recorded in the section "Result" when it is run, and a
  failed threshold reopens this decision (it does not change the threshold).
- **Date:** 2026-10-16
- **Sprint:** S22
- **Decision IDs:** D3 (final)
- **Related:** [ADR-0016](0016-transactional-outbox-and-idempotent-consumers.md) (the outbox, first step of D3),
  [ADR-0082](0082-storage-benchmark-specification-gate-g1.md) and [ADR-0083](0083-storage-benchmark-result-gate-g1.md) (how a
  threshold is set and judged), [ADR-0137](0137-executions-asynchronous-after-commit-and-what-they-keep.md)

## Context

D3 chose PostgreSQL itself as the first event transport and left the broker question open until the event processor existed
(ADR-0016: "to be benchmarked in Sprint 14 and revisited in Sprint 22"). Sprint 22 builds the processor: every record write
announces an event (`record.created`, `record.updated`, `record.deleted`), and the workflow module reacts to it. The question
D3 asked is whether the outbox and its polling relay carry that load, or whether a dedicated broker is needed.

## Decision

1. **Stay on the outbox and the relay.** Events are rows written in the transaction of the change; the relay claims them with
   `FOR UPDATE SKIP LOCKED` and a lease and hands them to `EventHandler` beans (ADR-0016). The workflow handler
   (`workflow.record-events`) is one more consumer. No broker, no second transport, no change to the publisher and handler
   contracts. A broker stays possible later behind the same contracts.
2. **Nothing is added to the request that writes a record.** The processor runs after the commit, in the relay. The write path
   is not touched; the only coupling is the event that was already announced. This is structural, and an architecture test keeps
   `data` from depending on `workflow`.
3. **The handler is cheap when there is nothing to do.** It asks `MetadataResolver.workflows(object)`, which is served from the
   organization's metadata snapshot (one read of the metadata version, no other query), and ends there when the object has no
   active workflow. With workflows it inserts one execution per matching workflow version with
   `on conflict do nothing` on the unique key (ADR-0137).
4. **Thresholds, written before any measurement and never moved afterwards** (ADR-0082). Machine: the benchmark machine of
   ADR-0083 (PostgreSQL 18, 14 processors, 7 GB), relay settings as written in the result file. One organization has 20 active
   workflows on the written object (10 on `RECORD_UPDATED`, 5 on `FIELD_CHANGED`, 5 that do not match the events); a control
   organization has none.

   | # | Measure | Pass |
   |---|---------|------|
   | T1 | Sustained events per second through the relay with the workflow handler on, one instance, events that match at least one workflow; the backlog must not grow over 10 minutes | ≥ 100 per second |
   | T2 | The same, events for an organization with no workflow | ≥ 200 per second |
   | T3 | Three instances, as T1 | ≥ 1.8 × T1 |
   | T4 | Delay from the commit of the event to its execution row, at half of T1's rate | p95 ≤ 3 s and p99 ≤ 10 s |
   | T5 | p95 of a record write (one transaction that changes a record row and writes its event, 8 concurrent writers) while the relay drains events with the workflow handler doing work (the organization with 20 workflows), against the same load while it drains events of the organization with none | ≤ 1.05 ×; the write path itself is unchanged (see 2), so this measures only what the handler's work adds to the writers through the shared database |
   | T6 | The handler for an organization with no workflow, after its marker insert | no query of its own and p95 ≤ 1 ms |

5. **If a threshold fails**, the decision is reopened with the numbers: the first answer is tuning the relay (batch size, poll
   interval, handler threads, which are settings), the second a dedicated broker. A broker is brought to the product owner
   before it is built.

## How it is measured

`benchmark/workflow/run-event-benchmark.ps1` (outside the build and outside CI, like the benchmark of ADR-0082). It needs the
local services and the application running with the `local` profile (the real relay and handler are what is measured). It
creates the organizations, objects, workflows and records with SQL, writes the events the way the publisher does (the same
columns, in the same kind of transaction as a record change), lets the real relay and handler drain them, and reads the delays
from the rows (`outbox_event.created_at` against `workflow_execution.created_at`). The writers of T5 are `pgbench` clients inside
the database container. `-Size smoke` proves it runs; the default is the size of the thresholds. The result is appended to this
ADR.

## Result

Not yet run. See "Status".

## Consequences

- No new infrastructure to operate for automation; the database the platform already runs carries the events.
- Latency is the poll interval plus handling time; ordering is not guaranteed, and a handler tolerates both (ADR-0016). A
  workflow reads the record when it runs, not the value at the event, so a late or reordered event cannot act on a stale value
  (ADR-0137).
- The throughput of one relay is bounded by one poll per instance per interval and a batch handled one event after the other.
  The thresholds say how much that has to carry; settings (`platform.outbox.batch-size`, `poll-interval`) are the first lever.

## Alternatives considered

- **A dedicated broker now:** a second system to run, secure, monitor and keep consistent with the database; the outbox exists
  precisely so a broker is not needed until a measurement says so.
- **`LISTEN`/`NOTIFY` to wake the relay:** not durable; polling stays the base and a notification could be an optimization later
  (ADR-0016).
- **Running the workflow inside the request's transaction:** would slow every write and tie a user's request to automation
  (ADR-0137 keeps validation and authorization in the transaction and everything else after the commit).

## References

- `platform-app/src/main/java/app/platform/workflow/internal/WorkflowEventHandler.java`
- `benchmark/workflow/`
