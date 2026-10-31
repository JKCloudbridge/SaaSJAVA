# ADR-0227: Which failures of a send are tried again, and how long to wait (S29)

- **Status:** Accepted
- **Date:** 2026-10-31
- **Sprint:** S29
- **Related:** [ADR-0142](0142-retry-policy-and-backoff.md), [ADR-0220](0220-the-outbound-flow-and-its-reliability-line.md), [ADR-0228](0228-repeat-safe-sends-and-the-extra-headers-of-an-adapter.md)

## Context

Sprint 28 made one attempt and ended the delivery (ADR-0220). An outage was paid for by every record written during it.
The workflow engine already classifies failures by type and waits with a jittered, capped backoff (ADR-0142); the sends
need the same shape with the failures of a network call.

## Decision

1. **Classification is closed and never reads a message.** The input is the stable reason of the network layer
   (`TestReason`) or the status number; what the other side said is never looked at. `RetryPolicy` (pure) returns a kind
   (retry or permanent), a stable reason and what the attempt shows about the other side.
2. **Retried:** a timeout, a connection that could not be opened or broke, a name that did not resolve, a platform-side
   credential store that was unavailable, and the statuses 408, 429, 500, 502, 503 and 504. **Never retried:** every other
   status (a 400-class refusal, 401 and 403, a redirect, any other 5xx), a certificate failure, an address the rules refuse,
   a credential that is missing or unusable, an answer too large, a record that cannot be mapped or written. **Unknown is
   permanent.** 500 is in the list on purpose: a failing service most often answers 500 during an outage, and the cost of a
   wrong retry is bounded by the attempts.
3. **Backoff** is `min(max, initial x multiplier^(n-1) + jitter)`: 5 s, doubling, capped at 15 minutes, jitter 0.2, at
   most 5 attempts (`platform.integration.reliability.retry.*`, every number inside a ceiling checked at start). The wait
   is kept in the row (`run_at`); no thread or connection holds it.
4. **Retry-After** (a number of seconds) lengthens the wait only for 429 and 503, up to a ceiling of one hour; a date or
   a negative or absurd number is ignored.
5. **A retry reads the record as it is now** and maps it under the mapping version the delivery is bound to: a waiting
   delivery never carries a stale payload, and a change of a mapping never rewrites one that waits.
6. **The attempts used up end the delivery as a dead letter** (ADR-0231), with the reason of the last failure.

## Consequences

The arithmetic and the table are tested without a database (`RetryPolicyTest`, with properties). A new failure the network
layer can report is a compile error in the classification until it is placed.
