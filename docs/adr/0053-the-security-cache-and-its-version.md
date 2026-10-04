# ADR-0053: The security cache and the security version

- **Status:** Accepted
- **Date:** 2026-10-04
- **Sprint:** S9
- **Related:** [ADR-0040](0040-effective-permissions-and-the-read-contract.md), [ADR-0050](0050-the-permission-decision-api.md), [ADR-0021](0021-brute-force-protection-and-rate-limits.md); delivery plan S9, S13

## Decision

`Permissions` and `Decisions` keep their contracts. Behind `Permissions.effective` and `Permissions.data` there is now a small cache of the
computed answer, kept **together with the organization's security version**, and a cached answer is served only while the version still
equals the one it was computed under.

1. **The security version** is a counter per organization (table `security_version`, migration V028). A database trigger on every table that
   decides an answer raises it **in the same transaction** as the change: `profile`, `access_policy`, `member_access`,
   `member_access_policy`, `member_grant`, `public_group`, `public_group_member`, `public_group_access_policy`, `object_permission`,
   `field_permission`, `licence_assignment`, `membership`. Triggers, not services, so that no code path (a service, a script, a repair by
   hand) can change those tables and forget the counter. `SecurityVersionIT` lists the tables, fails when one has no trigger or the set differs,
   and proves each table raises the counter. Left out on purpose: `security_role` (visibility only, never an ability), `licence_pool` (the
   size of a pool decides nothing) and `licence_type` (changed only by migrations and by adding a type, which changes no existing answer).
2. **The consistency bound is "the next question after the commit", on every instance.** Every question reads the version first (one small
   lookup), then the cached answer if its version is equal, else the rows. There is no timer and no message that can be lost, so there is
   nothing to expire late. A change that is committed raises the version before anyone can see the change; a reader that read the version
   before the commit and the rows after it keeps an answer that is at least as new as its version, and the next question recomputes.
3. **What the caller's own transaction sees.** A transaction that has already written something (it has a transaction identifier) never
   uses the cache and never fills it: it reads the rows, so it sees its own uncommitted change, and nobody else is handed a view that may roll
   back. Tested in `SecurityCacheConsistencyIT` (own change visible, invisible to another reader, nothing kept after a rollback).
4. **Where it lives: in each instance's memory, bounded** (`platform.security.cache.max-entries`, default 10000, least recently used goes
   first). **Redis is not used.** The version in the database is what makes an answer correct; a shared copy of the answers would add a second
   place to go wrong and one more network call for no gain, and the delivery plan's "local + Redis" is therefore a deliberate difference
   (flagged in the delivery plan). Consequence: **Redis being down cannot make an answer wrong**, because it is not involved
   (`SecurityCacheRedisOutageIT` proves it with Redis paused).
5. **A switch turns it off:** `platform.security.cache.enabled=false`. `SecurityCacheIT` runs three real instances (cache on, cache on,
   cache off) through every kind of change and requires the same answer from all three at every step.
6. **What is cached:** a member's abilities (`ABILITIES`) and abilities plus permissions on data (`EFFECTIVE`). **Not cached:** the
   last-manager guard, licence limits, every write path (they read the database), the group closure, the object catalogue, and
   `Entitlements.enabled` (see the cost note: one read, so a cache would cost as much as it saves). No answer to "may this be changed" is
   ever a cached "yes" of a request that mutates something beyond the ability check that starts it.
7. **Metrics:** `platform.security.cache{result=hit|miss|bypass}`.

## Cost note for whoever tunes this later (read before changing)

The cache is **not free**. Every question pays **one extra database read** (the version lookup, an indexed lookup of one row). It pays off
only because the answers it saves are several reads: abilities take about seven reads, permissions on data more (the matrices of the
profile, the policies and the grants are loaded). A hit therefore costs about one read where a miss costs seven or more.

- When **not** to cache: an answer that one read can compute (`Entitlements.enabled`, a single flag). The version read would cost the same.
- If the extra read ever shows in a measurement, the options are, in this order: (a) read the version **once per request** and reuse it
  (not done: the version can change inside a request, and the own-transaction rule needs a fresh look at "has this transaction written");
  (b) keep a copy of the version in Redis and read that instead (adds a lag that must then be bounded and tested: do not do it without
  a measured need and an ADR); (c) switch the cache off (`platform.security.cache.enabled=false`) and read the rows, which is always correct.
- The metrics above say how often it hits. A hit rate that stays low means the cache is paying for the version read and saving nothing.
- Sprint 13 (the metadata cache) should reuse this design: a version per organization, raised by triggers, keyed into the cache.

## What was weighed

- **Publish and subscribe invalidation (Redis).** Faster to notice a change on other instances, but a lost message means a stale "yes"
  until something else evicts it; fixing that needs the same version anyway. Rejected as the mechanism, not excluded as a later
  optimization on top (it could evict early; the version stays the guarantee).
- **A time limit on entries.** A bound in seconds is a window in which a revoked ability still works. Rejected: the requirement is the
  next question.
- **Raising the counter in the Java services.** One forgotten write path and the cache serves for ever. Rejected for triggers.

## Consequences

- Sprint 7 and 8 guaranteed staleness zero by reading the rows every time; the guarantee is now "the next question after the commit",
  which is the same for any question that starts after the change.
- Writes to the tables above take a row lock on the organization's version row for the rest of their transaction. All of them already
  hold the organization's access lock, so no new waiting appears; a script that writes these tables outside the services will queue behind
  an administrator's change.
- A rolling deployment is safe: old and new instances agree on the version in the database.
- Tests: `SecurityCacheTest` (rules), `SecurityVersionIT`, `SecurityCacheIT`, `SecurityCacheConsistencyIT`, `SecurityCacheRedisOutageIT`;
  each guard was checked to fail under a quick mutation (commands in the Sprint 9 manual testing document).
