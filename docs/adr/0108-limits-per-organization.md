# ADR-0108: Limits per organization

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S16
- **Related:** [ADR-0003](0003-rls-defence-in-depth.md), [ADR-0083](0083-storage-benchmark-result-gate-g1.md) (condition 3), [ADR-0105](0105-filters-and-orders-without-an-index-on-large-objects.md), [ADR-0109](0109-the-benchmark-as-a-regression-suite.md)

## Context

The benchmark showed that one organization running a dozen non-stop sessions made the others up to 22 times slower (run 1) and 1.7 times (run 2). The owner decided on 2026-10-12 that limits per organization are built in Sprint 16, before two real customers share a database.

## Decision

1. **Concurrent requests.** At most `max-concurrent` API requests of one organization run at the same time **on one instance** (default **6**). The prompt's recommendation of 16 would not have protected anything: the database connection pool has 10 connections by default, so one organization at 16 could still take all of
   them. The limit is deliberately below the pool, and a deployment that raises the pool raises the limit with it (`PLATFORM_DB_POOL_SIZE`, `PLATFORM_LIMIT_CONCURRENT`).
2. **A request over the limit waits briefly and then is refused, never queued without bound.** It waits up to `max-wait` (2 seconds) for a turn, but only `max-waiting` (6) requests of an organization may wait at once; the next is refused immediately, so a flood by one organization occupies at most 12 request threads
   and cannot starve the others of threads. A refused request gets `429 RATE_LIMITED` with `Retry-After` and "This organization has too many requests in progress", and did nothing.
3. **Who is limited and when.** The filter sits in the API security chain **after the caller is known as a member** (`UserContextFilter`): a stranger cannot use up an organization's turns, and the platform host, the public endpoints and the probes (another port) are not limited. Per instance, not shared across
   instances: the database is the shared resource and the time limit below is what holds across instances.
4. **Statement time.** Every transaction of an organization carries `statement_timeout` set with its tenant setting in the same round trip (`TenantSessionListener`): **5 seconds when the transaction only reads, 30 seconds when it changes something**. It is set in the database for the transaction, so it holds on every
   instance and for background work in an organization's context (filling an index, emptying the recycle bin). A statement that exceeds it is cancelled by PostgreSQL and answered as an unexpected error without internals.
5. **Settings and overrides.** `platform.tenancy.limits.*` with `overrides` by the organization's identifier (a plan decides the numbers later; the platform console that edits them is not built now). Test profile: far out of the way, so tests that run many requests at once are not refused; the tests of the limits set small numbers.
6. **Observation without naming.** Metrics `platform.limits.refused` (count) and `platform.limits.wait` (time), **without an organization tag**; the log says that an organization reached its limit (the organization is in the log context, at most every ten seconds), never what it asked.

## Consequences

- One organization flooding the server is bounded by 6 running + 6 waiting requests per instance and 5 or 30 seconds per statement; the benchmark measures the noisy-neighbour ratio with and without the limit (ADR-0109).
- A legitimate burst of more than 12 simultaneous requests from one organization on one instance gets some 429s; a client retries after the header. The numbers are settings.
