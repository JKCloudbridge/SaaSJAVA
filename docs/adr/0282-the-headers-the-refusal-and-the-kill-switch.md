# ADR-0282: The headers, the refusal, and the kill switch (S32)

- **Status:** Accepted
- **Date:** 2026-11-23
- **Sprint:** S32
- **Related:** [ADR-0011](0011-api-conventions.md), [ADR-0108](0108-limits-per-organization.md), [ADR-0247](0247-versioning-and-deprecation-policy-of-the-public-api.md), [ADR-0248](0248-publishing-the-contract-the-committed-document-and-how-it-is-generated.md), [ADR-0280](0280-rate-limits-and-quotas-the-algorithm-keys-cost-and-paths.md)

## Decision

1. **The headers are the product.** Every answer of a counted operation carries `RateLimit-Policy` and `RateLimit`, in the form
   of the current IETF draft on rate-limit headers (structured fields, one item for each window):
   `RateLimit-Policy: "minute";q=600;w=60, "day";q=500000;w=86400` says the limits (`q` the quota, `w` the window in seconds) and
   `RateLimit: "minute";r=120;t=33, "day";r=499000;t=40000` says what is left (`r`) and the whole seconds until the window ends
   (`t`). The draft's two-header form was chosen over the older `RateLimit-Limit` / `-Remaining` / `-Reset` triple because it can
   state two windows at once, which a rate and a daily quota need, and because it is the form the standard is moving to. The item for
   the minute is the **tighter** of the caller's own and the organization's (the one with less left), named by its window and never by
   whose it is.
2. **A refusal is `429 RATE_LIMITED`** in the error model, with `Retry-After` in **whole seconds** (rounded up, never below one:
   the time until the window that was full ends) and the same two headers. **One fixed sentence, and never which key was full
   (the organization or the application):** the header tells the number, which is all a well-behaved client needs.
   A request refused for its caller's own number is not charged to the organization.
3. **The public contract gains the headers and the 429 wording, additively:** the two headers on every response of a counted
   operation but the 401 (a request refused for a missing token was never counted), `Retry-After` on the 429, and a 429 whose
   words name both causes. `PublicContractBaselineTest` accepts the change as an addition; no path, status, parameter or property
   was removed or retyped.
4. **A well-behaved client** reads `RateLimit` on every answer, slows down before `r` reaches zero, waits `Retry-After` seconds on
   a 429, spreads bulk calls out and remembers that a bulk call costs its records; `docs/public-api.md` says so.
5. **The kill switch** `PLATFORM_RATE_LIMITS_ENABLED` (`platform.limits.public-api.enabled`, read at start): false makes the headers
   disappear and no request be refused for a limit; the usage page says the limits are off. It needs a restart of the application
   and nothing else. It is a different switch from `PLATFORM_LIMITS_ENABLED` of ADR-0108, which governs requests in progress.

## Consequences

- A client that follows the headers is never refused. A client that does not is refused with the time to wait.
- The headers are on the 404 and 403 of a counted request too, because the request was counted.
