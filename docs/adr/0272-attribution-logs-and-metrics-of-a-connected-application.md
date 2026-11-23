# ADR-0272: Attribution, logs and metrics of a connected application (S31)

- **Status:** Accepted
- **Date:** 2026-11-23
- **Sprint:** S31
- **Related:** [ADR-0012](0012-observability.md), [ADR-0054](0054-audit-v1-record-source-and-read-model.md), [ADR-0267](0267-a-connected-application-acts-as-the-member-who-registers-it.md), [ADR-0271](0271-where-the-token-of-a-connected-application-may-go.md)

## Decision

1. **The principal carries the application.** The token's attributes name the application by its public client identifier
   (`cid`, not a secret) and its registration (`crid`), plus `api_scopes` and, when it has a list, `api_objects`. A person's
   own token has none of them.
2. **The request log line names it.** `ClientTokenAccessFilter` sets the logging context key `clientId` for the rest of the
   request and the request attribute that the access record reads, so the line "request completed" carries `client_id`.
   Nothing else is added to the line: no path beyond the route of the public API, no value, no token.
3. **The audit trail says "via client X" next to the member, without changing the tenant context.** The record services keep
   writing as before. `JdbcAuditRecorder` adds the attribute `via_client` to every record written while the logging context
   holds a client identifier (unless the record already has one or has no room). No module passes it along and the shared
   `TenantContext` record, which every module uses, does not change shape. The audit viewer shows attributes, so an
   administrator sees the member and the application together.
4. **The management of applications is audited by identifiers and counts:** `clients.created`, `clients.updated`,
   `clients.secret_created`, `clients.secret_rotated`, `clients.secret_revoked`, `clients.disabled`, `clients.enabled`,
   `clients.removed` and `clients.token_refused`. Never a secret, a hash, a name or a description (typed text); the audit
   attribute keys avoid the words the record refuses (so `client`, not `credential`). Their audience is the organization
   (`AuditAudience`), with the reason visible. A refusal at the token endpoint is audited only for an application that exists
   and at most once for each reason and window.
5. **Metrics are counts by result and never by client.** `platform.clients.tokens{result}` (`issued` or the OAuth error code)
   and `platform.clients.auth.failures{reason}` (a closed set of reasons). **A client identifier is never a metric tag**: it
   would make the cardinality the customer's to choose.
6. **"Last used" is written at most once a minute per application,** on the secret row that made the grant, so that no row
   becomes a hot spot (a counter in the shared counters decides; a failure to write is logged by its type and never fails the
   request). The list shows the latest use of any secret of the application, at most one minute behind.

## Consequences

- Reading the trail: find the record, read `via_client`, find the application in Setup, Connected applications.
- `ClientLogsAreCleanIT` plants a canary secret and a canary token and requires them absent from logs, audit trail, errors,
  metrics and every table except the hash column's hash.
