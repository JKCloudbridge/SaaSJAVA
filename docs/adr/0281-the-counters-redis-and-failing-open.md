# ADR-0281: The counters, Redis, and failing open (S32)

- **Status:** Accepted
- **Date:** 2026-11-23
- **Sprint:** S32
- **Related:** [ADR-0021](0021-brute-force-protection-and-rate-limits.md), [ADR-0268](0268-the-client-registry-and-the-secrets-of-a-connected-application.md), [ADR-0280](0280-rate-limits-and-quotas-the-algorithm-keys-cost-and-paths.md)

## Decision

1. **The counters are the platform's own, extended.** `Counters` (fixed windows, shared in Redis, per instance in memory when Redis
   does not answer) gained what the limits need and sign-in did not: `add(key, window, amount)` (a bulk call costs its records;
   the first addition starts the window, in one script, so a counter can never be left without an expiry) and
   `remainingMillis(key)` (the headers say when a window ends). Both have a default, so a counter that knows only the first two
   operations still works.
2. **A Redis outage degrades the precision and never refuses the API.** `ResilientCounters` counts for each instance when Redis
   does not answer, leaves it alone for a pause after a failure, logs one warning when the fall-back starts and one when Redis is
   back (the exception's type only), and counts the operations answered locally (`platform.identity.ratelimit.degraded`), which
   is what an alert should watch. The limit therefore still bounds a caller per instance, which is weaker across a group.
3. **Limits fail open on their own faults and closed on abuse.** If counting throws (the counters are resilient already, so this
   is a bug and not an outage) the request goes on, the fault is counted (`platform.limits.errors`) and logged once by type; a
   limiter that refuses everyone is a worse failure than one that lets a request through. A read of the override that fails gives
   the defaults and is not cached. The kill switch (ADR-0282) works with a restart of the application and nothing else.
4. **The override is read through a short cache** (`platform.limits.public-api.override-cache`, 10 seconds): a database read for
   every request would be the hot spot this design avoids. A change made on an instance is seen at once by it and by another
   within that time.
5. **Memory is bounded** (`LocalCounters` drops expired windows and empties itself above a size, which only ever gives an
   attacker a fresh allowance, never wrongly refuses anyone).

## Consequences

- Sign-in, the token endpoint and the invitation limits behave as before: the new operations are additions.
- `RateLimitRedisOutageIT` pauses a Redis container and shows the API answering, with a limit, throughout.
