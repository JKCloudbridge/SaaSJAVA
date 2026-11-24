# Runbook: rate limits and quotas on the public API

For the person who operates the platform. What a customer does with the limits is in [../public-api.md](../public-api.md)
(section 9a); the decisions are in ADR-0280 to ADR-0283 and ADR-0285.

## Settings

| Setting (environment variable) | Default | What it does |
|---|---|---|
| `platform.limits.public-api.enabled` (`PLATFORM_RATE_LIMITS_ENABLED`) | `true` | `false` is the kill switch: the rate-limit headers disappear and no request is refused for a limit. Read at start: restart (or roll) the instances. It is **not** the switch of ADR-0108 (`PLATFORM_LIMITS_ENABLED`, requests in progress) |
| `platform.limits.public-api.application-per-minute` | `600` | requests a minute for each connected application and for each member's own token |
| `platform.limits.public-api.organization-per-minute` | `3000` | requests a minute for one organization, whoever asks |
| `platform.limits.public-api.organization-per-day` | `500000` | requests a day for one organization (the day ends at midnight UTC) |
| `platform.limits.public-api.ceilings.per-minute`, `.per-day` | `10000000`, `1000000000` | the highest numbers an override may set |
| `platform.limits.public-api.override-cache` | `10s` | how long an instance keeps an organization's override before it reads it again |
| `platform.limits.public-api.bulk-max-cost` | `200` | the most a single request may cost |

The defaults are generous on purpose: a developer's machine, the tests of other sprints and the benchmark scripts never meet
them. The `test` profile sets them far out of the way.

## Switching it off, and back

Set `PLATFORM_RATE_LIMITS_ENABLED=false` and restart. Use it only if the limiter itself is wrong and refuses requests it
should not: it stops every limit on the public API. (A fault of the limiter never refuses a request: it lets the request through,
counts `platform.limits.errors` and logs once by the type of the fault.) Switch it on again the same way.

## Raising or lowering one organization's limits

Platform console, the organization, "Limits on the public API" (platform administrators; support may read): type the numbers
that change (an empty box keeps the default), give a reason (kept in the platform's audit trail only; never shown to the
organization), and set. "Return to the defaults" removes the override. A change is seen by the instance that made it at once and
by the others within `override-cache`. The organization sees the numbers on its own "API usage" page, never the reason. Audit
records: `limits.override_set`, `limits.override_removed`.

## Reading it

Metrics (micrometer), **never an organization, an application or a member as a tag**:

- `platform.limits.requests{result}`: `allowed` and `limited`. A rise in `limited` is a client that does not read the headers
  or a limit that is too small for a customer's real use: look at the organization's usage page before raising anything.
- `platform.limits.quota.exhausted`: requests refused because the day's quota was used.
- `platform.limits.errors`: faults of the limiter that let a request through. Should be zero; page on a rise.
- `platform.identity.ratelimit.degraded`: operations counted by this instance alone because Redis did not answer.

The log line of a refused request is the ordinary 429 line of the error model (request ID, organization, client identifier); no
token or key is in it.

## What a Redis outage does

The counters fall back to each instance's memory after the first failed call and the pause that follows (the log says so once
when the fall-back starts and once when Redis is back). Each instance then limits for itself: the limit still bounds a caller,
but a group of instances together may accept up to the limit times the number of instances in a window. The API is never
refused for the outage. When Redis is back the count is shared again from the next window.

## A client reports a 429 it does not understand

Read its headers: `RateLimit` says what is left and when the window ends, `Retry-After` how long to wait. The answer never says
which limit was full; the organization's usage page shows the numbers. A bulk call costs its records, so ten bulk calls of 200
use 2,000 of the 3,000 a minute of the organization.
