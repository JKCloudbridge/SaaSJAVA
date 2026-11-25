# Runbook: an incident, the first page

Sprint 33 (decisions in [ADR-0294](../adr/0294-operating-it-alerts-logs-and-backups.md)).

## When to use this

"What happened to organization X's request twenty minutes ago?" or any report that something went wrong for one person or one
organization.

## 1. Get the request identifier

Every answer carries `X-Request-ID`, and every error body carries `requestId` (and `traceId` when tracing is on). Ask the person for
the identifier from the error message on screen; failing that, the time and what they did.

## 2. Follow it

- **The log** (the environment's application log group): search the request identifier. Each line has the request, trace and tenant
  identifiers in its context. The lines name classes of failures and identifiers, **never** a value, a body, a token or an address.
- **The trace**, if tracing was on for it (`PLATFORM_TRACE_EXPORT`): the trace identifier follows the request from the browser through
  the application to the database.
- **The audit trail of the organization** (ability `audit.view`, the audit viewer): who changed what, when, by identifiers and counts.
  For a platform person's actions, the platform's own audit trail (platform administrators).
- **The release at the time:** the log line `The platform is running: version, commit` of the task, and `/actuator/info`.

## 3. The rules while you look

- **Reading an organization's data on behalf of a platform person** is only through support access: the organization grants it, it is
  time-limited and in the audit trail (`SupportAccess.require`). Do not read data any other way. The database's server log can quote
  a value inside a constraint error, so only the owner reads it.
- **Never paste** a token, a password, a key or a value into a ticket, a chat or a note. Quote identifiers.
- A suspected leak of a key or a credential is a rotation ([signing-key-rotation.md](signing-key-rotation.md),
  [kek-rotation.md](kek-rotation.md), the credentials section of [secrets.md](secrets.md)), then a notice to the people affected.

## 4. Suspending an organization now

Platform console, the organization's page, suspend (platform administrators; the reason is bounded and audited). Its sessions end.

## How I know it worked

You can say, with identifiers and times, what the request was, which release answered it, what it did and how it ended; the
organization's administrators have what they need from their own audit trail; nothing secret was written down.
