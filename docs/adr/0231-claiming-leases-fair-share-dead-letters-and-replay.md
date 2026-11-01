# ADR-0231: Claiming, leases, fair share, dead letters and replay (S29)

- **Status:** Accepted
- **Date:** 2026-10-31
- **Sprint:** S29
- **Related:** [ADR-0015](0015-row-level-security-implementation.md), [ADR-0135](0135-the-workflow-dispatch-system-scope.md), [ADR-0141](0141-idempotency-exactly-once-and-replay.md), [ADR-0143](0143-dead-letters-parked-events-and-redrive.md), [ADR-0164](0164-a-fair-share-of-the-queue.md)

## Decision

1. **The worker (`OutboundWorker`) replaces the sender.** It claims due deliveries of every organization in one statement
   with `FOR UPDATE SKIP LOCKED` and a lease, fairly (each organization with work is served by turns, at most 20 of its
   deliveries per look, ADR-0164), in the new **system scope `integration_dispatch`**. The policies of `integration_delivery`
   admit it for reading and the claim and no other table does; only the worker may enter it (an architecture test). The
   work of a claimed row runs in the organization's own tenant context.
2. **Three short steps per attempt:** a transaction prepares the request and asks the gate (below), the call is made with
   no transaction open, a transaction records the end. An instance that dies leaves a lease that ends; any instance claims
   the delivery again, writes the lost attempt as `INTERRUPTED` and repeats it with the same key (ADR-0228).
3. **The gate (`FlowGate`)** asks, in this order and under the lock of the connection's flow row: the circuit (ADR-0229),
   the concurrency of the connection, the rate (ADR-0232). A refusal costs nothing (no token, no probe slot) and the
   delivery waits for the time the answer gives.
4. **Dead letters:** a delivery whose attempts are used, or whose failure is permanent, ends `DEAD_LETTERED` with the
   reason of the last failure, audited by identifiers and counted. A dead letter is final unless an administrator sends it
   again.
5. **Replay (ability `integrations.replay`):** the *same* delivery becomes `PENDING` again as a **new generation** with its
   attempts counted afresh (the database allows exactly that move). It re-reads the record, derives the operation from the
   link (a completed effect is not repeated) and carries a new key. **Two clicks make one:** the second finds it no longer
   dead and is refused. Bulk replay takes the oldest first, at most 500 (100 by default).

## Consequences

Replay needs no second table and the history of every generation stays in the attempt log. `DeadLetterReplayIT`,
`WorkerCrashIT`-style recovery and `MultiInstanceIT` hold the rules.
