# ADR-0210: The connection lifecycle and connection testing

- **Status:** Accepted (development decision of Sprint 26)
- **Date:** 2026-10-30
- **Sprint:** S26
- **Related:** [ADR-0012](0012-observability.md), [ADR-0208](0208-the-integration-definition-and-the-address-rules.md), [ADR-0209](0209-integration-identity-permissions-and-tenant-isolation.md)

## Decision

### The lifecycle

Five states, one pure table (`ConnectionMachine`, with a property test), held again by a database guard
(`platform_integration_connection_guard`, V083) whatever the application does:

| From | Event | To |
|------|-------|----|
| `UNTESTED` | edit | `UNTESTED` |
| `UNTESTED` | test passed | `TESTED` |
| `UNTESTED` | test failed | `UNTESTED` |
| `TESTED` | edit | `UNTESTED` |
| `TESTED` | test passed / failed | `TESTED` / `UNTESTED` |
| `TESTED` | switch on | `ENABLED` |
| `ENABLED` | switch off | `DISABLED` |
| `ENABLED` | test passed / test failed / reported failure | `ENABLED` / `FAILING` / `FAILING` |
| `FAILING` | switch off | `DISABLED` |
| `FAILING` | test passed / test failed | `ENABLED` / `FAILING` |
| `DISABLED` | edit | `UNTESTED` |
| `DISABLED` | switch on | `ENABLED` |
| `DISABLED` | test passed / failed | `DISABLED` |

"On" means `ENABLED` or `FAILING`. **Only `ENABLED` is usable.** `FAILING` is "meant to be working, is not right now": its
subscriptions and rights are suspended until a test passes or it is switched off.

- **Switching on needs a successful test of the current settings.** The definition's fingerprint (ADR-0208) is stored with the
  row; a test stores the fingerprint it tested (`tested_fingerprint`); `TESTED` and `ENABLED` require the two to be equal,
  enforced by the application *and* by the database. **Any change of address, sign-in, credential name or settings changes the
  fingerprint** and so voids the test (`TESTED` and `DISABLED` go back to `UNTESTED`). A change of only the label, the events
  or the scope keeps the test.
- **A connection that is on is frozen.** Its definition cannot be changed and it cannot be removed (service and database); it
  is switched off first. This keeps "the definition in use" and "the definition that was tested" the same thing at every
  instant, without a second draft mechanism.
- **Switching off stops all use at once.** The state is read afresh by every question (`Integrations`), nothing is cached, so
  the next question on any instance sees `DISABLED`.
- A **removal** is a soft delete, allowed only while off; the name is free again afterwards; the row is kept for audit.
- **Concurrency.** Every change carries the version read and a stale one is `CONCURRENT_MODIFICATION`; switching off retries
  because it must not be lost to a concurrent test. The test result is stored *against the fingerprint that was tested*: a
  result for a definition changed meanwhile is dropped and the answer says `changed_during_test`, so a test can never vouch
  for a definition it did not test.

### Connection testing

One call to the external system, **bounded in every direction**, and answered with a **stable reason**.

- **Outside any transaction.** `ConnectionTesting` runs three steps: a short transaction decides who may ask, loads the
  definition and takes the claim; the call (no transaction, no database connection held); a short transaction stores the
  result. The call is in the organization's own context (the secret is resolved in its scope).
- **Rate limited.** One test per connection per `platform.integration.test.min-interval` (default 5 s), decided by a
  conditional update on the database clock, so two instances cannot both test a connection (`429` otherwise); at most
  `platform.integration.test.max-concurrent` (4) tests at once on an instance.
- **A hard deadline.** The whole test (name lookup, connect, handshake, read) has one deadline: the connector's wish, at most
  `platform.integration.test.timeout` (10 s). The call runs on its own virtual thread and the request waits for it with a
  timeout, so even a lookup that never returns ends the test.
- **Size cap, no redirects, no body.** No more than `platform.integration.egress.max-header-bytes` (16 KiB) of an answer is
  ever read, and only as far as the end of the headers: the body is never read, kept or returned. A `3xx` is `redirect_refused`
  and the target is never contacted.
- **Reasons, not messages.** `TestReason` is a closed set of keys with the platform's own sentences: `ok`,
  `address_not_allowed`, `name_not_found`, `connect_failed`, `tls_failed`, `timed_out`, `response_invalid`,
  `response_too_large`, `redirect_refused`, `unexpected_status`, `authentication_rejected`, `credential_missing`,
  `credential_unusable`, `credential_store_unavailable`, `changed_during_test`, `unexpected`. **Never the remote body, a header,
  a status number in words, or a library's exception message** (an unexpected exception goes to the error tracker by class name
  only, and the answer is `unexpected`).
- **The one exception to "no external call from a request".** The standing rule keeps external calls out of a person's
  save, approval and record work. An administrator's explicit, rare, rate-limited, bounded test is the deliberate exception,
  because the person is waiting for exactly that answer. The queue that moves such work off the request, with retries and
  circuit breaking, is Sprint 29.
- **Audit and metrics.** `integration.tested` (result, reason, state, connection identifier, connector key);
  `platform.integration.test{result,reason}` (counter) and `platform.integration.test.duration{result}` (timer); the tags are
  fixed keys, never an address, a host, a name or a person.
- **Credentials.** Resolved at the moment of use through the shared kernel's `SecretsStore` if there is one (none until
  Sprint 27: `credential_store_unavailable`), put into exactly one header built by the framework (`Authorization: Bearer`,
  `Authorization: Basic` over `user:password`, or the named API-key header), checked to be visible ASCII with no line break
  (otherwise `credential_unusable`), destroyed after.

## Consequences

- A test is the only way into `TESTED`/`ENABLED`; the database refuses every shortcut, as the guard test
  (`ConnectionGuardIT`) shows with direct statements as the application role and as the owner.
- `REPORT_FAILURE` exists in the table for Sprint 29 (runtime failures) and has no caller yet; it is a transition of the
  machine, not an endpoint.
