# ADR-0280: Rate limits and quotas: the algorithm, the keys, the cost and the paths (S32)

- **Status:** Accepted
- **Date:** 2026-11-23
- **Sprint:** S32
- **Related:** [ADR-0021](0021-brute-force-protection-and-rate-limits.md), [ADR-0108](0108-limits-per-organization.md), [ADR-0232](0232-rate-limiting-per-connection.md), [ADR-0244](0244-the-public-api-its-address-its-closed-table-and-the-rules-that-carry-over.md), [ADR-0246](0246-keyed-writes-the-idempotency-key-and-its-memory.md), [ADR-0267](0267-a-connected-application-acts-as-the-member-who-registers-it.md)

## Context

After Sprint 31 any software can call the public door with its own identity. Without limits one customer, or one connected
application, can use up what the others share. This is a different problem from the Sprint 29 gate: that gate takes a row lock
for each call, which is right for a few outbound calls a second to one target and **wrong for every request of a public API**
(a database row would be a hot spot).

## Decision

1. **Where it lives.** The limits are counted in `identity.internal` (`RateLimits`, `RateLimitMath`, `PublicRateLimitFilter`,
   the override store), next to the shared counters of Sprint 3, the token that says which client a request stands for and
   `PublicApiAccessFilter`. This keeps every module edge as it was: `publicapi` is not given a dependency on `identity`, and the
   platform console reaches the override through the shared-kernel contract `RateLimitSettings`.
2. **Algorithm: fixed windows in the shared counters.** The **rate** is a fixed window of a minute that starts at the first
   request that finds none, so the time it has left is exactly the time to reset. The **quota** is the **calendar day in UTC**:
   its window ends at the next midnight and its key carries the date, so a client can plan by it. Both are one atomic
   `INCRBY` in Redis (`Counters.add`), so a window never has more than the limit accepted on it by more than nothing (the accepted
   requests add up to at most the limit: `RateLimitMathTest`, a property).
3. **Keys, and the order they are charged in.** (a) the caller's own rate: **per connected application** when the token is one,
   **per member** for a person's own token; (b) the organization's rate; (c) the organization's quota for the day. The caller is
   charged first and only a request that fits it is charged to the organization, so an application that is over its limit and
   keeps asking cannot use up what the others share. So a thousand applications cannot exceed the organization, and one
   application cannot starve the rest. A request refused for the organization's number has already been charged to its caller,
   which is right: that caller did ask.
4. **Cost.** A request costs **1**. A bulk call costs **its records**, at most the size of a bulk call (200), read from the
   body at the filter before the controller (the body is held to the edge's limit and passed on whole). A keyed repeat that is
   answered from memory **still counts**, because it reached the server (`PublicRateLimitIT`). A bulk call that does not fit is
   refused whole.
5. **Which paths count.** The **public prefix only**, and only a request that is an operation of the closed table
   (`PublicApiAccess`), made by a token that is allowed to reach it (a request refused for a missing token or scope is not counted).
   Not counted: the description document, the token endpoint, the internal interface and the pages of the application.
6. **Who sets the numbers.** Settings with safe, generous defaults (`platform.limits.public-api.*`): **600 a minute for each
   application and each member's own token, 3,000 a minute and 500,000 a day for an organization.** The platform can set other
   numbers for one organization (ADR-0283). The plan does not decide them: the licensing module has no place for such numbers
   and none was invented; a plan-based scheme is a later decision.
7. **Apart from ADR-0108.** The limit on requests *in progress* and on statement time stays exactly as it is and is a separate
   cause with its own words and no headers; a client that is refused for either sees a 429 and `Retry-After`, and the contract
   says both.

## Consequences

- No database row on the path of a request, and an exact answer to "how much is left" in every answer.
- A Redis outage makes the count less precise and never refuses a request (ADR-0281).
