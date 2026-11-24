# Limits and webhook benchmark (Sprint 32, ADR-0285)

Measures what the exit of Sprint 32 asks for: **limits enforced under load**, and a webhook sender that is fast enough, fair
between organizations and loss-free. The thresholds **L1 to L4** (limits) and **W1 to W4** (webhooks) are in
[ADR-0285](../../docs/adr/0285-the-thresholds-of-the-limits-and-webhook-benchmark-fixed-before-any-number.md); they were
written before any measurement and are **never changed after a number is seen**. The harness is **not** part of the Maven build
and **not** run by CI.

## Run it

Needs the JDK (the load generator is one source file the JDK runs), Windows PowerShell, and a running application with the plan
feature `integrations` for the organization. Make a connected application (Setup, Connected applications) with all scopes and
ask for its token, or use the access token of a signed-in person; give it to the script as `-Token` (or `LIMITS_TOKEN`).

```powershell
cd platform
.\benchmark\limits\run-limits-benchmark.ps1 -Size smoke -OrganizationHost tenant-a.localhost -Token $env:LIMITS_TOKEN   # proves the harness runs; NOT a measurement
.\benchmark\limits\run-limits-benchmark.ps1 -OrganizationHost tenant-a.localhost -Token $env:LIMITS_TOKEN -Bases http://127.0.0.1:8080,http://127.0.0.1:8082,http://127.0.0.1:8083
```

| Switch | Meaning |
|--------|---------|
| `-Bases` | The base addresses of the instances, comma separated; requests are spread over them (L1, W3 need three) |
| `-OrganizationHost2`, `-Token2` | A second organization's host and token (L2 and W4) |
| `-RedisContainer` | The name of the Redis container the instances use; L3 pauses it, then resumes it |
| `-KillCommand` | A PowerShell command that stops one instance at half of the webhook run (W3) |
| `-Mode` | `limits`, `webhooks` or `both` |

The result goes to `benchmark/results/<time>-limits-<size>/summary.md`; the exit code is 0 when every judged threshold was met, 2
otherwise. Record a judged result in ADR-0285 ("Result") with the machine, the number of instances and the settings.

## What it measures

| Threshold | How |
|-----------|-----|
| L1 | After the window of the application is fresh, a burst of three times its limit (read from `RateLimit-Policy`) is spread over the instances; accepted must not exceed the limit by more than 2 percent |
| L2 | A second organization's p95 alone, then while the first is hammered by 16 callers; at most 1.5 times (and 20 ms) |
| L3 | The Redis container is paused while 8 callers ask; no 5xx at all, p95 at most 2 seconds |
| L4 | 50 concurrent callers for the cheapest counted request; p95 at most 250 ms |
| W1 | A local receiver counts the deliveries made while records are created through the public door; at least 100 a second |
| W2 | Read from the receiver's arrival times at half of W1's rate (the harness prints the rate it reached) |
| W3 | Distinct delivery identifiers received equal the events created, with at most 1 percent duplicates, while an instance is stopped at half of the run |
| W4 | One organization has up to 10,000 deliveries queued behind a slow receiver; the other organization's first attempt p95 at most 3 seconds |

## Limits of the measurement (stated, not hidden)

It talks to a running application, so latencies include this machine's network and the application's request time, but not a
customer's software. The receiver is a local web service. W2 is a reading of W1's arrival times, not a separate run. A pass is a
pass only for the machine, the number of instances, the settings and the data size recorded with it. The harness was written in
Sprint 32 and **compiled and parsed, not run**: the first run of each mode is its smoke run.
