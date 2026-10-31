# ADR-0232: Rate limiting per organization and connection (S29)

- **Status:** Accepted
- **Date:** 2026-10-31
- **Sprint:** S29

## Decision

1. **A token bucket per connection, in the database** (the flow-state row, `RateLimiter` pure). It is handled under the
   row's lock, so every instance draws from the same bucket. Redis is not used: the limit must hold across instances
   without a second dependency, and an outage of Redis must not change what is sent.
2. **Two limits:** sends started per minute (default 6,000, ceiling 60,000) and sends under way at once (default 4,
   ceiling 32), counted from the held deliveries whose lease has not ended. The bucket holds at most one second's worth
   and at least one send.
3. **Settings per connection** (`PUT .../limits`, ability `integrations.operate`) can only be inside the platform's
   ceilings; a number above is refused with the ceiling in the words. They are operational, not part of the definition, so
   a connection that is switched on can be slowed without being switched off.
4. **A throttled delivery waits; it does not fail.** Its waiting reason is `throttled` and its next look is the time the
   bucket gave. Fair share between organizations is the claim's (ADR-0231); the limits are per connection.

## Consequences

Throughput of one connection is bounded by its bucket however many instances run, and the property test shows the
admitted sends never exceed the burst plus the rate times the time (`RateLimiterTest`, `RateLimitIT`).
