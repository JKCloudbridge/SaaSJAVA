# ADR-0024: The mail queue and notification v0: a platform-level queue, a relay with retries, no secret at rest

- **Status:** Accepted
- **Date:** 2026-10-01
- **Sprint:** S4
- **Related:** [ADR-0016](0016-transactional-outbox-and-idempotent-consumers.md), [ADR-0022](0022-platform-level-identity-tables-and-audit-v0.md),
  [ADR-0023](0023-sign-up-verification-and-password-reset.md)

## Context

Sign-up, password reset and the lock notice must send e-mail **asynchronously and with retries**: a slow or unreachable mail
server must neither slow nor fail a request, and a message queued before an outage or a restart must still go out. The
delivery plan says "through the asynchronous outbox", but the outbox (ADR-0016) belongs to exactly one tenant by design (a
tenant column, row level security, handlers that run under the event's tenant), and the mails of Sprint 4 are requested on the
platform host, where there is no tenant. Mail also differs from an event: it calls a system outside the database, so a retry
can send twice, and its content may carry a secret (a one-time link).

## Decision

1. **A separate, platform-level queue**: the table `mail_queue` (V011) with its own relay, modelled on the outbox relay. The
   outbox is not stretched to platform scope: its guarantee "every event belongs to exactly one tenant" stays true. The table
   is added to `SchemaConventions.PLATFORM_TABLES` (a security decision, ADR-0015; no tenant column, no row level security;
   it holds an address and a kind of mail, never a message).
2. **The contract lives in the shared kernel** (`MailQueue`, `MailRequest`, `MailTemplate`), like the audit contract, so a
   module that needs a mail does not depend on `notification`; the implementation is the `notification` module. A request
   is written in the caller's transaction (a rolled-back change sends nothing). `MailRequest` refuses variable names that
   suggest a secret or a link, so a mistake fails in a test instead of putting a secret into the queue.
3. **The queue holds no secret and no message.** A row says "this kind of mail for this address" (`SIGN_UP_REQUEST`,
   `PASSWORD_RESET_REQUEST`, `PASSWORD_CHANGED`, `ACCOUNT_LOCKED`). The relay decides what it becomes **at send time**
   (ADR-0023): for a link, it asks the identity module for a fresh one-time token (`AccountTokens`, which only the
   notification module may use, an architecture rule), builds the text and sends. So a mail that waited through an outage
   holds no token at rest, the state of the account at send time decides what is sent, and the request path stays identical
   for every address. Texts are fixed English sentences with a few named places, in plain text and HTML (every inserted value
   escaped); there is no personal text in any message and no template engine.
4. **The relay** (every instance may run one; switch `PLATFORM_MAIL_RELAY_ENABLED`) claims due rows with one statement
   (`FOR UPDATE SKIP LOCKED`), a lease (default 2 minutes), the attempt counted at the claim, and the row version as the claim's
   token. A message the server could not take is retried with exponential backoff (30 seconds doubling to 15 minutes, 20
   percent jitter, at most 10 attempts); a claim never completed (the instance died) expires and any instance takes the row
   over. After the last attempt, or when a mail is still unsent after 24 hours (its link would be stale), or when its attempts
   were all spent without reporting back, it becomes a **dead letter** that stays 30 days for a person to look at, is counted
   (`platform.notification.mail{result}`), audited and reported through the error-tracking hook (hence the module edge
   `notification` to `observability`). Sent and suppressed rows are removed after 7 days.
5. **Delivery is at least once.** A crash between the server accepting a message and the row being marked sent can send it
   twice. For a link mail the second message carries a newer token that replaces the first; for a notice a duplicate is
   harmless. The queue cannot do better without the mail server's help, as the outbox cannot for effects outside the database.
6. **SMTP** through the framework's mail sender (`spring.mail.*`; credentials from the environment, never a file). Outside a
   developer machine the connection must upgrade to TLS (`PLATFORM_SMTP_STARTTLS_REQUIRED`, default true) and short timeouts
   keep a slow server from holding the relay. The sender address (`PLATFORM_MAIL_SENDER`) and the public base address
   (`PLATFORM_PUBLIC_BASE_URL`, https except on a developer machine) are mandatory settings with no default; links always point at
   the platform host. A deployment sets these and the SMTP host (the list is in the engineering guide and the manual steps).
7. **Nothing personal or secret in logs, records or the queue.** The relay logs the row ID, the kind, the attempt and the
   type name of a failure, never the address or the library's message (which can quote both); the mail library's own logger is
   pinned to warnings because at debug it prints recipients (found by `AccountFlowsLogsAreCleanIT`); the request types print as
   `[redacted]`. Audit: `notification.mail.sent`, `.suppressed` (with the true reason), `.dead`.
8. **Local and tests.** The local environment already has a mail catcher (SMTP 1025, web interface and API on 8025); the
   `local` profile sends to it. Integration tests use a real SMTP catcher container, pause it to stand in for an outage, and
   read the messages back.

## Consequences

- E-mail is reliable across outages and restarts without a broker, with the database the platform already operates.
- A second small relay exists beside the outbox's. Sprint 22 (event processor, broker question) may merge them behind one
  interface; the contracts here would not change.
- The v0 has no in-app notifications, no per-tenant sender or branding, no languages other than English, no bounce handling
  and no unsubscribe (every mail is transactional and about the recipient's own account). Each is a later story.
- The mail relay's throughput is one poll per instance per interval and a batch handled one after the other; not benchmarked.
- **Enforced by tests**: `MailRelayIT` (outage, instance death, dead letters, expiry, concurrent pollers send once,
  retention, no address in a log, no secret in a row), the schema test, `PlatformRulesTest` (only the notification module
  creates link tokens), `MailRequestTest`, `MailTextsAndComposerTest`.

## Alternatives considered

- **The outbox with a platform scope**: weakens its single-tenant guarantee for one use.
- **Storing the finished message (with its link) in the queue**: a copy of the table would be usable for account take-over
  during the hours it is kept; encrypting it needs a key the platform does not have yet (Sprint 27).
- **Sending inside the request**: couples the answer's time to the mail server and to whether a mail is sent.
- **A hosted mail service's API**: a named commercial product is not chosen in this project; SMTP is the neutral interface.

## References

- Migration: `platform-app/src/main/resources/db/migration/V011__create_mail_queue_table.sql`
- Code: `platform-shared-kernel/src/main/java/app/platform/sharedkernel/mail/`, `platform-app/src/main/java/app/platform/notification/`
- Tests: `platform-app/src/test/java/app/platform/notification/internal/`
