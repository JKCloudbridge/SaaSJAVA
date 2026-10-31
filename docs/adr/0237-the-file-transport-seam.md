# ADR-0237: The seam for a transport that is not the web (S29A)

- **Status:** Accepted
- **Date:** 2026-10-31
- **Sprint:** S29A
- **Related:** [ADR-0207](0207-the-connector-abstraction-and-the-adapter-spi.md), [ADR-0208](0208-the-integration-definition-and-the-address-rules.md), [ADR-0227](0227-retry-classification-and-backoff-for-sends.md), [ADR-0235](0235-protocol-coverage-soap-and-what-follows.md)

## Decision

1. **A connector says which transport it needs; the framework carries it out.** `Connector.transport()` (default `HTTP`)
   returns a value of `Transport` (`HTTP`, `SFTP`). An adapter still opens no socket, reads no secret and touches no
   database. A connector type of file transfer implements `FileFlowConnector`, which adds only what is about *content*:
   `layout(settings)` (the directories, the format, the timing) and `read(settings, content, maxRows)` (a whole file into
   entities). Writing a row is the existing `send`: the request it returns carries the content as its body and an ending as
   its path; the name is the framework's (ADR-0240).
2. **The seam inside the module is `FileTransport` and `FileVisit`** (package-private, in `integration.internal`, not in
   the adapter package): a visit is one opened session with `list`, `stat`, `rename`, `read` and `create`, each of which
   succeeds or throws `TransportFailure` with a stable `TestReason` and nothing else (no message, no cause, no stack). The
   sender, the poller and the tester see only these two types, so the work above them is tested against a plain fake and
   the one implementation (`SftpTransport`) holds every rule about the other side.
3. **The worker is not forked.** `OutboundWorker.call` branches once, at the top: `if (transport == SFTP) putFile(...)`.
   The HTTP path below it is unchanged line for line (the tests of Sprint 29 pass unchanged). `putFile` returns the same
   `Answer` the web client returns: a written file is a `201` whose body names the entity, a refusal is an `Answer` with a
   `TestReason`. So the classification (`RetryPolicy`), the retries, the breaker, the limits, the attempt log, the links and
   the monitoring screen apply to a file send without a line of change.
4. **How an outcome maps to an `Answer`** (`SshOutcome`, pure, from the stage and the status code, never from a message):

   | What happened | Reason | Retried | Health for the breaker |
   |---|---|---|---|
   | name not found, connection refused, no route | `name_not_found`, `connect_failed` | yes | unhealthy |
   | time ran out, a read timed out | `timed_out` | yes | unhealthy |
   | the connection broke or closed after it was opened | `response_invalid` | yes | unhealthy |
   | the key shown is not the pinned one | `host_key_mismatch` | **never** (and the connection is stopped) | neutral |
   | the server refused the credential | `authentication_rejected` | **never** (and the connection is stopped) | neutral |
   | no common ground before the key was checked | `ssh_failed` | no | unhealthy |
   | the directory does not exist | `path_not_found` | no | alive |
   | the server did not allow it | `permission_denied` | no | alive |
   | the general failure of the file protocol (a full disk, a store that refuses, a name taken) | `file_failed` | yes, bounded | unhealthy |
   | a status nobody listed | `file_failed` | yes, bounded | unhealthy |

   Unknown is retried a bounded number of times and then set aside, which is what "permanent" means for a send: nothing is
   retried for ever, and nothing is lost (a dead letter can be sent again).
5. **A connection stopped by a wrong key or a refused credential** moves from *on* to *on, but failing* through the move its
   life already had for "a use of the connection failed" (`ConnectionFailures`, `recordTest` with the reason shown as the
   reason of the last test). It is not usable until a test passes or it is switched off, so the queue waits and nothing is
   dead-lettered for a fault that is not in the data. This applies to the file transport only; a `401` from a web service
   still dead-letters the delivery as before (ADR-0227).
6. **The address of a file server is `sftp://host[:port]`** (`EndpointRules.parse(raw, policy, Transport)`). Every rule of the
   web address holds: no user information, no query, no path (the directories are settings), a public host (every resolved
   address checked), the development hosts of ADR-0208 (refused by the `prod` profile). Port 22 is the default and any port
   from 1 to 65535 may be named. The rule "https only" has a purpose, no plain text on the wire; `sftp` is encrypted, so it
   needs no exception. `Endpoint` carries its `scheme`; the written form of an address is canonical per scheme.

## Consequences

A second protocol is one more value of `Transport`, one more implementation of `FileTransport` (or its sibling) and one
branch at the top of the worker. A need that fits none of them cannot be expressed and is never half-supported.
