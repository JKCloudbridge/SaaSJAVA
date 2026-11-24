# ADR-0279: Retries, the breaker, the fair claim, and switching off (S32)

- **Status:** Accepted
- **Date:** 2026-11-23
- **Sprint:** S32
- **Related:** [ADR-0164](0164-a-fair-share-of-the-queue.md), [ADR-0227](0227-retry-classification-and-backoff-for-sends.md), [ADR-0229](0229-the-circuit-breaker-of-a-connection.md), [ADR-0231](0231-claiming-leases-fair-share-dead-letters-and-replay.md), [ADR-0232](0232-rate-limiting-per-connection.md), [ADR-0278](0278-deliveries-attempts-reasons-order-replay-and-retention.md)

## Decision

1. **The classification and the schedule are Sprint 29's, with the same defaults, because they are the same classes.**
   `RetryPolicy` reads the *kind* of failure and never what the receiver said: 2xx is done; 408, 429, 500, 502, 503, 504 and
   network failures are tried again; **`410 Gone` and every other 4xx are permanent**; a refusal to be redirected and a failed
   handshake are permanent; unknown is permanent. `Retry-After` is honoured up to a ceiling (one hour). The wait is
   `min(max, initial × multiplier^(n−1) + jitter)`: 5 attempts, 5 seconds, ×2, at most 15 minutes, 20% jitter by default
   (`platform.webhooks.retry.*`). The wait is in the row (`run_at`), so no worker or connection holds it.
2. **The breaker and the limiter are per subscription and applied under the lock of its row** (`WebhookGate`, the same pure
   `CircuitBreaker` and `RateLimiter` as `FlowGate`, with the state in the subscription's own row because the flow-state table
   belongs to connections): the circuit first, then at most 10 calls in flight to the address, then a token bucket of 20 deliveries a
   second. A refusal costs nothing and the delivery waits; **an open circuit never sends and a throttle never fails a delivery.**
3. **A claim with a lease and fair between organizations.** The worker enters the system scope `webhook_dispatch` only to claim
   and count (only `WebhookWorker` may; an architecture test), takes at most a few due deliveries of one organization per claim
   by turns (`platform.webhooks.worker.per-organization`, 20) and runs each in that organization's own tenant context. One
   organization with thousands of events cannot fill a claim. A delivery whose instance died comes back when its lease ends,
   with the lost attempt written down; the repeat carries the same delivery identifier.
4. **Switching off after a long failure (the one decision the plan left open).** A subscription that fails **without one
   success for seven days** (`platform.webhooks.disable-after`) is **switched off by the platform**: the state becomes `DISABLED`
   with the reason `auto_disabled`, the administrators and the member it acts as get a notice (kind `WEBHOOK_STOPPED`, a fixed
   text of the platform, no name and no address, which cannot be switched off), an audit record `webhooks.auto_disabled` is
   written, and **nothing is deleted**. Every attempt that does not succeed counts toward the run (a refusal as well as an
   outage); one success ends the run. Switching it on again is an administrator's act and **needs a new verification** of the
   address.
5. **While a subscription is off or stopped, its queue holds to the bound** (ADR-0278 item 7) and a delivery waits and is looked
   at again after `platform.webhooks.queue.idle-recheck`.

## Consequences

- A dying address is asked politely and then left alone; a dead one stops costing anything after a week.
- The Sprint 29 reliability tests pass unchanged because the shared classes are untouched.
