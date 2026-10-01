# ADR-0021: Brute-force protection: uniform answers, constant work, the lock, rate limits and the outage rule

- **Status:** Accepted
- **Date:** 2026-10-01
- **Sprint:** S3
- **Stories:** S3-SEC-05, S3-SEC-06, S3-SEC-08, S3-SEC-09, S3-SEC-10, S3-SEC-11
- **Related:** [ADR-0019](0019-authentication-implementation.md), [ADR-0020](0020-passwords-and-hashing.md)

## Context

A sign-in form is attacked in three ways: **guessing** (many passwords for one account), **spraying** (one common password over many
accounts) and **probing** (finding out which addresses have an account, from the answer or from how long it takes). A protection that
locks accounts hard turns into a way to lock out anybody, so the protections must not become a weapon. The user decided, at the start
of the sprint: a **short capped lock plus a per-source limit**, and **degrade, do not stop, when Redis is down**.

## Decision

1. **One answer for every failure (S3-SEC-05).** Unknown address, wrong password, locked, suspended, deactivated and not yet verified
   all give the same status, headers and body (401, code `UNAUTHENTICATED`, the message "The email address or the password is not
   correct."), and none sets a cookie. The true internal reason goes to the audit record only (S3-SEC-11). A test compares six cases
   byte for byte (apart from the request and trace IDs). A refusal for too many attempts (429) is the same for an account that exists
   and one that does not.
2. **The same work on every path (S3-SEC-06).** Whatever the state of the account, exactly **one** password verification runs before
   the answer is decided: against the real hash when there is one, against a hash nobody can match when there is not (also for a
   locked or disabled account, an account never verified, and a password longer than any valid one). A test counts the verifications
   on every path, and a timing test compares unknown, locked and disabled with a wrong password (a ratio between 0.5 and 2 with a real
   hash cost); both tests were checked to fail when the dummy work is removed. They are guards against an early return, not a
   side-channel analysis.
3. **The lock (S3-SEC-08).** The state lives in the database (`user_credential`): from **5** consecutive failures the account is
   locked for **1 minute**; every further failure doubles the lock up to a **cap of 15 minutes**; the lock always ends by itself.
   Rules that keep it from being a weapon: an attempt made while locked is refused and **not counted**, so hammering a locked account
   does not lengthen it; the counter starts again after a success, or **15 minutes after the later of the last failure and the end of
   the lock** (measured from the end of the lock, otherwise a cap-length lock would erase the count and the attacker would never climb
   past the first step). The failure is counted under a row lock, so simultaneous failures on several instances are counted one after
   another. Even the right password is refused while locked.
4. **Rate limits (S3-SEC-09), fixed windows counted in Redis** so every instance sees the same numbers: per source address, **20 failed
   sign-ins per 10 minutes** and **60 attempts per minute**; per identifier (the normalized address, only its hash is a key), from any
   source, **10 attempts per minute**. They run before any password work, so a refused attempt costs almost nothing, and the identifier
   limit applies to unknown identifiers exactly as to real ones. An IPv6 source is reduced to its /64, so rotating inside one
   subscriber's range does not help. The source is the connection's address, or the first `X-Forwarded-For` entry only when a
   deployment declares a trusted proxy (`platform.identity.trust-forwarded-for`).
5. **When Redis is down, sign-in keeps working (decision of the user).** After a failed call Redis is not asked again for five
   seconds (calls time out in 250 ms), and each instance counts for itself in memory. The lock is in the database and is unaffected.
   The fall-back is **visible**: one warning when it starts, one when Redis returns, and a counter
   `platform.identity.ratelimit.degraded` that an alert should watch. A test pauses a real Redis and proves sign-in, the lock and the
   per-source limit all still work, and that the shared counters are used again afterwards.
6. **Behaviour under a distributed attack (S3-SEC-10).** Many sources against one account: the lock engages after five failures and
   grows to the cap; the identifier limit shields the account's hashing from the flood; refused attempts are not counted; the lock is
   never longer than 15 minutes. One source spraying many accounts: stopped after its failure limit, and the refused attempts are not
   even looked at. The owner is never banned: the identifier window is one minute and the lock ends by itself (tests wait the windows
   out). Changing a password through the signed-in endpoint is limited as well (ADR-0020).
7. **Audit (S3-SEC-11).** Every failure, lock, refusal and success is recorded with its true reason, the source (network prefix) and,
   for a failure of an unknown account, only a hash of the typed address (a password typed into the address field must not reach the
   trail).

## Known limits (stated, not hidden)

- **A persistent attacker keeps an account locked** for as long as they keep failing about once per lock period: each lock is short
  and capped, but they can start the next one. This is the price of the chosen policy (a delay, not a ban). What limits the harm: the
  per-source limit makes it costly; the owner is notified by e-mail and can reset the password (both arrive with Sprint 4: **a lock
  does not block a reset**); the lock never lasts beyond 15 minutes at a time. A *source-aware* lock (do not lock out sources that
  recently signed in successfully) was considered and not chosen for this sprint; it can be added without changing the interfaces.
- The per-source limit counts a shared network (an office behind one address) as one source.
- Without Redis the limits are per instance, so with N instances an attacker gets up to N times the allowance until Redis returns.
- Fixed windows allow a burst across a window boundary (up to twice the allowance for a moment).

## Consequences

- The numbers are configuration (`platform.identity.lockout.*`, `platform.identity.rate-limit.*`); the defaults are the ones above.
- Redis is now a runtime dependency with a defined failure mode; deployments must supply its address and password (Sprint 33).

## Alternatives considered

- **Permanent lock until an administrator or a reset**: a trivial way to lock anybody out; rejected.
- **CAPTCHA or proof-of-work after failures**: needs a front-end component and a third-party service; not in this sprint.
- **Refuse sign-in when Redis is down**: safest against abuse, but an outage of Redis would stop every sign-in; rejected by the user.
- **Counting limits in the database**: a write per request on a hot path and contention; Redis is the better tool for counters.

## References

- Code: `platform-app/src/main/java/app/platform/identity/internal/LocalCredentialsProvider.java`, `LockoutPolicy.java`,
  `SignInLimiter.java`, `ResilientCounters.java`, `RedisCounters.java`, `LocalCounters.java`, `ClientSource.java`
- Tests: `SignInIT`, `ConstantWorkIT`, `DistributedAttackIT`, `RedisOutageIT`, `TwoInstancesIT`, `LockoutPolicyTest`,
  `SignInLimiterTest`, `CountersTest`, `ClientSourceTest`, `PlatformProviderAdapterTest`
