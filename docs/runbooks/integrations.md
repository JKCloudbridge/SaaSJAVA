# Runbook: connections to external systems

Sprint 26 (ADR-0207 to ADR-0211). For the operator and for an organization's administrator. The framework can describe, test
and switch on a connection; it cannot yet hold a real credential (Sprint 27), map data or send a record (Sprint 28).

## Who may do what

| Ability | What it allows |
|---------|----------------|
| `integrations.view` | see the connections, their state, the last test and the connector types |
| `integrations.manage` | create, change, switch on or off and remove (needs `integrations.view` too) |
| `integrations.test` | call the external system once (needs `integrations.view` too) |

The organization also needs the plan feature `integrations` (it is in the trial plan; a platform administrator adds it to a
plan or gives one organization an override in the console). Without it every endpoint answers `409`.

## The life of a connection

`UNTESTED` (made, or changed since the last good test) → test → `TESTED` → switch on → `ENABLED`.
`ENABLED` → switch off → `DISABLED`. A failed test of a connection that is on makes it `FAILING` (not usable) until a test
passes or it is switched off. A connection that is on cannot be changed or removed. A change of the address, the sign-in, the
name of the credential or a setting voids the last test.

## Reading the result of a test

The answer is a stable reason and a fixed sentence; the platform never shows what the other system said.

| Reason | What to check |
|--------|---------------|
| `ok` | nothing |
| `address_not_allowed` | the name resolves to a private, loopback, link-local or otherwise non-public address; the platform will not connect there |
| `name_not_found` | the name does not resolve from the platform's network |
| `connect_failed` | nothing listens on that port, or a firewall drops the connection |
| `tls_failed` | the certificate is not trusted, has expired, or does not match the name |
| `timed_out` | the service did not answer within the connection's wait (at most 10 seconds) |
| `response_invalid`, `response_too_large` | the service did not answer in HTTP, or its headers exceed 16 KiB |
| `redirect_refused` | the check path redirects; point the connection at the final address (redirects are never followed) |
| `unexpected_status` | the service answered, but with a status the connection does not count as working |
| `authentication_rejected` | the service answered 401 or 403 to the credential |
| `credential_missing` | no secret exists under that name for this organization |
| `credential_unusable` | the secret cannot be sent as a header (a space in a bearer token, a line break, no `:` in a user name and password) |
| `credential_store_unavailable` | the secrets store cannot be read, or **no store is installed (before Sprint 27 this is the answer for every connection that needs a credential)** |
| `changed_during_test` | the connection was changed while it was tested; test again |

A test is limited to one per connection every `platform.integration.test.min-interval` (default 5 s); a second answers `429`.

## Settings (`platform.integration.*`)

| Setting | Default | Meaning |
|---------|---------|---------|
| `limits.max-connections` | 20 | live connections per organization |
| `egress.connect-timeout`, `egress.read-timeout` | 3 s, 5 s | per step; one overall deadline applies as well |
| `egress.max-header-bytes` | 16384 | the most that is read of an answer |
| `egress.development-hosts` | empty | exact hosts that may be used over plain HTTP and may be private. **The `prod` profile refuses to start when this is not empty.** For a developer machine and automated tests only |
| `test.timeout` | 10 s | the longest a whole test may take |
| `test.min-interval` | 5 s | the shortest time between two tests of one connection |
| `test.max-concurrent` | 4 | tests at once on an instance |

Environment variables are the usual Spring forms of these names (for example `PLATFORM_INTEGRATION_TEST_TIMEOUT`).

## Network

The platform opens connections from the application instances. Allow outbound HTTPS from them to the external systems an
organization uses and nothing else you do not want reachable: the platform refuses private addresses itself, but a network rule
is a second line. Do not put a proxy that follows redirects between the platform and the internet unless you have
read ADR-0208. Instance metadata services must remain unreachable from the application (the platform also refuses the
link-local address).

## Metrics and audit

`platform.integration.test{result,reason}` and `platform.integration.test.duration{result}`; `platform.integration.change{action}`.
Audit kinds `integration.created`, `.updated`, `.enabled`, `.disabled`, `.removed`, `.tested` (identifiers, keys and counts; never
an address, a label or a credential name). A rise of `reason=tls_failed` or `timed_out` across organizations points at the
network of the platform, not at one organization.

## What to do when

- **Every test of every organization fails with `name_not_found`:** the application's DNS. Check the resolver of the host.
- **`credential_store_unavailable` everywhere:** expected until Sprint 27 installs a store.
- **A connection is stuck `FAILING`:** run a test; if it passes it is `ENABLED` again; if the service is gone, switch it off.
- **An administrator cannot change a connection:** it is on. Switch it off first.
