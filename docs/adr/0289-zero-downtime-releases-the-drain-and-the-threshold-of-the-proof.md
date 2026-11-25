# ADR-0289: Zero-downtime releases, the drain, and the threshold of the proof (S33)

- **Status:** Accepted (the threshold is fixed here before any number was seen and is never moved)
- **Date:** 2026-11-24
- **Sprint:** S33
- **Related:** [ADR-0016](0016-transactional-outbox-and-idempotent-consumers.md), [ADR-0133](0133-event-processing-stays-on-the-outbox-decision-d3-final.md), [ADR-0165](0165-chaos-and-load-tests-and-the-d3-measurements.md), [ADR-0286](0286-deployment-topology-the-ingress-and-the-forwarded-headers.md), [ADR-0288](0288-migrations-as-a-job-the-compatibility-rule-and-rollback.md)

## Decision

1. **Rolling, not blue/green.** The container service starts the new tasks beside the old ones (minimum healthy 100 percent,
   maximum 200 percent) and removes an old one only when a new one is healthy; a release that does not become healthy is rolled back
   by the service's circuit breaker. Blue/green would double the database problem (two versions against one schema with a second
   full environment) and gains nothing here, because the expand/contract rule ([ADR-0288](0288-migrations-as-a-job-the-compatibility-rule-and-rollback.md))
   already lets two versions share one schema. The order of a release: migration job (new image), then the application service, then
   the web service.
2. **A stop is an orderly sequence, not a kill.** On the stop signal: (a) readiness turns false at once and the instance keeps
   serving for the **shutdown delay** (`ShutdownDelay`, 10 s in `prod`, 0 elsewhere), so the balancer, which learns by health check or
   by deregistration, stops sending before the server stops listening; (b) the workers drain in the same phase, concurrently; (c) the
   web server stops accepting and finishes what is in flight (graceful shutdown); (d) the process ends. The sidecar of the task stops
   **last**: the application container depends on it, and the container service stops containers in the reverse order.
3. **The worker drain contract** (`WorkerStop`, the one helper; `WorkersStopOrderlyTest` makes every `SmartLifecycle` bean use it).
   Each worker stops claiming, finishes the item in its hand and exits within **`WorkerStop.GRACE` (15 s)**; what is still running
   after that is interrupted and is recovered by lease expiry, which every worker already survives without repeating a completed
   effect (`WorkerCrashIT`; the orderly counterpart is `OrderlyStopIT`). Spring stops the beans of one phase one after the other when
   each blocks in `stop()`, so each worker answers `stop(Runnable)` by running its stop on its own thread: the phase takes as long as
   the slowest worker, and the phase timeout is a real bound. Some workers used 20 and 25 seconds of their own and the jobs used no
   wait at all (an immediate interrupt); all now use the one number.
4. **The numbers, in one rule** (held by `DrainNumbersTest` against the values files and the application's defaults):
   worker grace (15) < phase timeout (30); shutdown delay (10) <= phase timeout, and >= health-check interval x unhealthy
   threshold (5 x 2); the application's stop timeout (90) >= two phases + 15 and <= 120; the balancer's deregistration delay (60) >=
   delay + phase timeout (40); the balancer's idle timeout (130) > the longest time limit of any edge path class (120); the web
   server's own delay (10) >= interval x threshold and its stop timeout (30) >= delay + 15. Staging and production use the same
   numbers so what staging proves is what production does.
5. **Long operations.** An import, a spreadsheet upload and a file transfer in progress are requests like any other: the graceful
   shutdown waits for them up to the phase timeout; a longer one is cut and the client sees a failure, which the resumable import
   and the repeat-safe transfer already tolerate. The balancer's idle timeout is longer than the longest edge time limit.

## The threshold of the proof (written before any number was seen; never moved)

The exit of Sprint 33 is "deploy with live traffic causes no failed requests". In miniature, on the rehearsal (`release.ps1`): a
steady mix of reads, writes and page loads (about 100 a second through the ingress) runs for a baseline of 30 seconds, then through a
rolling release of every application and web replica, then for a settle time of 20 seconds.

- **A request FAILED** if it ended in a connection error or a timeout, a response of 500 or above, or a 401 (a token that was valid
  is refused, the sign of keys that differ between instances). An answer such as 400, 403, 404, 409 or 429 is an application-level
  answer, not a failure.
- **PASS = zero failed requests, and the p95 of the requests made during the release at most 2.0 times the p95 of the steady state
  before it.** Anything else is FAIL. The same rule judges the rollback, the scale check's loads and the rotations (their latency
  factor is not judged; their failed requests are). The result of each run is recorded in `Sprint 33.md` as counts and times only.
- The proof is for the machine, the number of replicas and the sizes recorded with it; it says nothing about a cloud's own
  balancer. The staging run of the same procedure (a manual step) is the proof for the cloud.

## Consequences

- A worker added later that does not use `WorkerStop` fails the architecture test; the stop time of an instance stays a plan.
- Every release needs at least two application tasks to keep capacity; with one task the service starts the new one first, which
  also loses nothing, but a failed new task leaves no spare.
- The web server's `/healthz` turns to 503 while it drains (its image entry point), the only change to the web side.
