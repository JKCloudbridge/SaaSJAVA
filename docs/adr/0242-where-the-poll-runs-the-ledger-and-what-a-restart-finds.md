# ADR-0242: Where the poll runs, the ledger, and what a restart finds (S29A)

- **Status:** Accepted
- **Date:** 2026-10-31
- **Sprint:** S29A
- **Related:** [ADR-0229](0229-the-circuit-breaker-of-a-connection.md), [ADR-0231](0231-claiming-leases-fair-share-dead-letters-and-replay.md), [ADR-0241](0241-picking-files-up-claim-check-whole-then-apply-in-batches.md)

## Decision

1. **A poll is a row, claimed with a lease, by the worker.** `integration_poll` (V089): one row per connection that picks files
   up, with the time of the next look, a lease (`locked_until`) and what the last look found (an outcome, a stable reason, the
   number of files seen waiting). It is made when such a connection is first switched on. **`OutboundWorker` claims the due
   ones** in the system scope it already has (`integration_dispatch`) with a skip-locked statement, and runs each in the
   organization's own tenant context on a thread of its own, up to `platform.integration.files.max-concurrent-polls`
   (default 4) per instance. The scope is entered by that one class only (the rule is unchanged and an architecture test
   also fails any other caller of `PollStore.claimDue`); `FilePoller` runs in the tenant context and enters no scope. The
   policy of the table lets the scope read and claim rows of `integration_poll` and nothing else.
2. **The breaker, the limits and the connection's state apply as to a send.** A look begins by asking: is the connection
   usable (`ENABLED`)? is there a live mapping for the entity? is the address allowed? may the gate admit it now (the circuit,
   then the concurrency, then a token, under the row lock of the flow state)? *A connection that is off never looks; an open
   circuit never looks; a throttle never fails the look* — the poll waits for the time the gate gave. A look ends by telling
   the gate how it went (alive, unhealthy, neutral), exactly as an attempt does, so a server that is down opens the circuit
   for sends and looks alike. A held look is shown with its stable reason (`connection_off`, `circuit_open`, `throttled`,
   `no_mapping`, `transform_failed`).
3. **The ledger.** `integration_file` (V089): one row per file taken, **written before the file is moved** (the intent),
   advanced in the transaction of the rows it counts, final when the file has been moved. It holds the hash of the name,
   size and time, the hash of the content, the number of rows, the progress, a state and a stable reason. **It never holds a
   file name, a path, a row or a value**: the name of a file is chosen by the other side, so it is data of the organization.
   The states and the moves between them are held by a trigger whatever the application does (`CLAIMING`, `CLAIMED`,
   `APPLIED`, `REJECTED`, `DONE`, `SET_ASIDE`, `ABANDONED`; progress never goes back; the number of rows and the content
   never change; a finished row is final).
4. **What a restart finds, and does.** Every look begins with the unfinished rows of the connection: `CLAIMING` with the file
   in processing becomes `CLAIMED`; `CLAIMING` without it is abandoned (the file, if it is still there, is in the inbox and
   is taken afresh); `CLAIMED` is applied from `rows_done` (the content is read again and must have the hash it had);
   `APPLIED` and `REJECTED` are moved. A look that is cut off (a restart, a lost connection, the deadline) therefore loses
   nothing and repeats no row. The lease of the poll (default 10 minutes) is what lets another instance take over; the
   rename that claims a file is what keeps two instances from ever having one.
5. **How a long listing or a huge file is bounded.** A look reads at most `maxListed` names (default 2,000), takes at most
   `maxFiles` files (50), reads a file up to `maxFileBytes` (25 MiB) and `maxRows` rows (10,000) and stops starting new work
   when its `deadline` (5 minutes, never past the lease) has passed; what is left waits for the next look, which comes at
   once if work is waiting. A visit to the server is a short session of its own for the claims, one per file for the read, one
   at the end for the moves, with no database transaction held across any of them.
6. **How often.** The connection says (`pollMinutes`, 1 to 1440, default 5) and the platform's floor holds
   (`platform.integration.files.min-interval`, default 1 minute, never below 5 seconds). A failure that waiting may mend is
   looked at again after the first backoff step (the breaker counts it); one that waiting cannot mend (a missing credential,
   a missing directory) waits for the connection's own schedule.
7. **Retention.** Finished rows are kept `ledgerRetention` (default 30 days, 1 to 400) and then removed in bounded batches
   by the look itself; that is also how long a file already applied is recognised if it comes back.
8. **One switch.** `platform.integration.files.enabled` (`PLATFORM_INTEGRATION_FILES_ENABLED`, default true) stops an
   instance from claiming polls; sending files is part of the outbound worker and is switched with it.

## Consequences

Sending and picking up share one place that decides whether a connection may act, so a pause, a throttle or a stop means the
same for both directions.
