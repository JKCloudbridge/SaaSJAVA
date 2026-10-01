# ADR-0012: Observability: logs, metrics, traces, error tracking, correlation

- **Status:** Accepted
- **Date:** 2026-10-01
- **Sprint:** S1
- **Extends:** [ADR-0001](0001-modular-monolith.md) with the `observability` module

## Context

The platform must answer "what happened to this tenant's request 20 minutes ago?" across the browser, the API and the
database. That needs the same identifier in every hop's logs, a trace that links the hops, metrics for operations, and a
way to learn about unexpected errors, without logging personal data and without tying the platform to any product.

## Decision

1. **Structured logs.** Standard output, one JSON object per line, in every profile except `local` (readable text; the variable named in
   `application-local.yml` switches to the deployment format) and `test`. Each line carries `@timestamp`, level, logger
   and the logging context: `requestId`, `traceId`, `spanId` and `tenantId`. The tenant ID is reserved: nothing sets it
   before Sprint 2, and it is never taken from client input (`LogContext` in the shared kernel is the one place to set
   it). Each request ends with one access record (`platform.http`: method, path without query string, status, duration).
   Logs never contain bodies, headers, tokens, passwords or personal data.
2. **Request ID** rules are in [ADR-0011](0011-api-conventions.md). The filter runs just inside the tracing filter, so a
   response and every log line carry both the request ID and the trace ID, also for failures.
3. **Distributed tracing.** The standard trace-context header (`traceparent`) is accepted and continued, so a trace that
   starts in the browser continues through the API into the database. Each request is a span (tagged with the request
   ID) and each SQL statement and connection checkout is a child span; **bind values are never recorded** (statements
   show placeholders). Default sampling is 10% in `prod` and 100% in `local`. Spans are shipped over the standard
   telemetry protocol (HTTP) only when `PLATFORM_TRACE_EXPORT=true`, to `PLATFORM_TRACE_ENDPOINT`.
   - **Finding:** in Spring Boot 4.1.1 the framework's general tracing export switch
     (`management.tracing.export.enabled`) also turns off trace-context propagation. With it off, the application
     neither continues an incoming trace nor passes one on, and nothing in the logs says so. It therefore stays on
     always; shipping spans is controlled by the exporter's own switch (see `application.yml`). `TracingIT` fails if
     propagation ever breaks.
   - The platform does not trust the caller's sampling decision as a security measure; an edge sampling policy is
     decided with the deployment (Sprint 33) and reviewed in Sprint 36.
4. **Database correlation.** At the start of every transaction the connection's `application_name` is set, for that
   transaction only, to `t=<trace id> r=<request id>` (a transaction listener; the setting reverts at commit, so pooled
   connections cannot carry one request's identifiers into the next, which a 120-request concurrency test proves). With the
   database log's line prefix showing the application name (the local compose file does), a slow or failing statement is
   attributable to one request. Cost: one extra round trip per transaction. The identifiers are sized to fit
   PostgreSQL's 63-character limit. Sprint 2 uses the same hook for the tenant.
5. **Browser.** The Next.js app starts tracing in the browser: the page's calls to its own origin become spans and carry
   `traceparent` (also when nothing collects spans). The browser logs one JSON line per API call with `requestId` and the
   `traceId` the API returned, in the same vocabulary as the backend. Spans reach the collector through the app's own
   origin (`/telemetry`), so no cross-origin rules exist.
6. **Metrics.** Standard HTTP, JVM and connection-pool metrics plus `platform.api.errors{code}` (every error response sent)
   and `platform.errors.unhandled{exception}` (unexpected failures). Scraped from the metrics endpoint on the **management
   port** (default 8081); `application` and `environment` tags on everything. The management port also serves liveness
   and readiness (`readiness` includes the database) and is not routed to the outside.
7. **Error tracking hook.** `ErrorReporter` (public API of the `observability` module) receives every unexpected error
   with the request and trace IDs. The platform ships one reporter that writes a structured ERROR record; a deployment
   that wants a hosted error tracking product adds a bean (all reporters are called, a failing one is isolated). No product is chosen
   or named here. The built-in reporter logs the exception types, the SQL state, and the call frames, **not exception
   messages** except those of the platform's own exceptions, because library messages routinely quote the data that
   caused the failure (a rejected email address, a parse input). `ErrorReporter` implementations that leave the process
   must scrub messages themselves.
8. **Module.** `observability` (`app.platform.observability`, allowed dependency: `sharedkernel`); its public API is
   `ErrorReporter`, `ErrorReport` and `ErrorTracker`.

## Consequences

- One search for a request ID or trace ID finds the browser line, the API lines and the database statements.
- Local development works without any collector; the compose file offers a trace viewer.
- The database stamp costs one round trip per transaction; the Sprint 14 benchmark must include it.
- Unsampled requests still carry IDs and still appear in logs, but produce no spans.
- Dashboards, alerts and on-call runbooks come with Sprints 25 and 36; this sprint provides the signals.

## Alternatives considered

- **A tracing agent attached to the JVM:** powerful but opaque, harder to test and to keep out of the container image;
  the framework integration is enough and is covered by tests.
- **Statement comments carrying the identifiers:** defeat prepared-statement reuse because every request produces
  different SQL text.
- **Setting the application name on every connection checkout:** an extra round trip per statement, not per transaction.
- **Logging the throwable as is:** simplest, but leaks quoted data into logs.

## References

- `platform-app/src/main/java/app/platform/observability/`, `platform-shared-kernel/.../logging/LogContext.java`
- `platform-app/src/main/resources/application*.yml`
- `platform-app/src/test/java/app/platform/observability/` (`TracingIT`, `StructuredLoggingIT`, `ErrorTrackerTest`, ...)
- `platform-web/src/lib/telemetry/`, `platform-web/src/lib/log.ts`, `infra/local/docker-compose.yml`
