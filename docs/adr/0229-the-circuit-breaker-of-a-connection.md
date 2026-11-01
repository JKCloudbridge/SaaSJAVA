# ADR-0229: The circuit breaker of a connection (S29)

- **Status:** Accepted
- **Date:** 2026-10-31
- **Sprint:** S29
- **Related:** [ADR-0210](0210-the-connection-lifecycle-and-connection-testing.md), [ADR-0227](0227-retry-classification-and-backoff-for-sends.md)

## Decision

1. **Runtime state, not a lifecycle state.** The breaker never changes the connection's `ENABLED`, never unfreezes it, and a
   connection that is off is not asked about the breaker at all. It lives in the table `integration_flow_state`, one row per
   connection, locked while it is decided, **so every instance agrees** (per-instance state would let each of three
   instances send the probe).
2. **States** closed, open, half open (`CircuitBreaker`, pure, with properties). The rule that opens it: **consecutive
   failures** (5, bounded 1 to 100). A failure is only what shows the other side is not answering: a timeout, a refused or
   broken connection, 408, 500, 502, 503, 504 and a certificate failure. A 429, a refusal to authenticate and a rejected
   record show it is alive and do not count; a failure on the platform's own side before anything was sent shows nothing and
   only frees a probe slot. A rate over a window is not built (the rule above is simple to explain and to test).
3. **Open for 60 s**, then **half open**: it lets 1 probe through (bounded 1 to 10); one success closes it, one failure
   opens it again. A probe that never reports is given up after 2 minutes. A late answer to a send made before it opened
   changes nothing.
4. **While open a delivery is queued, not failed and not dropped**; its waiting reason is `circuit_open`, and all the due
   deliveries of the connection are put back by one statement, so a pause costs one write.
5. **Forcing it** needs the ability `integrations.operate` (organization abilities only, no platform role): *force open*
   sends nothing, *force closed* never opens, *automatic* forgets the failures. Each is audited and counted; the screen asks
   first with the API's own sentence about what happens.

## Consequences

"A breaker that is open never sends, a throttle never fails, a disabled connection never sends" are three separate checks in
the worker, in that order, and each has its own test.
