# ADR-0238: The SSH library, and the socket the platform opens (S29A)

- **Status:** Accepted
- **Date:** 2026-10-31
- **Sprint:** S29A
- **Related:** [ADR-0208](0208-the-integration-definition-and-the-address-rules.md), [ADR-0237](0237-the-file-transport-seam.md)

## Decision

1. **One small, maintained, pinned library**, used by one class. The JDK has no secure shell, so a library is needed
   (a new dependency, with the reason here as the rules ask). The choice is a small SSH client library in its maintained
   fork: a single jar with no transitive dependency, a permissive licence (BSD style), active releases, and a hook that
   lets the caller supply the connected socket. Its exact version is in the parent POM only (`ssh-client.version`,
   2.28.8). The coordinates are in the build files and nowhere else.
2. **Vulnerability check (2026-10-31).** The project publishes no security advisory of its own. A report of September 2026
   concerns the parsing of a *known-hosts file* in versions up to 2.28.5, fixed in 2.28.6; the platform uses 2.28.8 and
   never reads a known-hosts file (the pinned key replaces it, ADR-0239). The dependency scan of CI (which fails the build
   on a fixable high or critical finding) is the standing check; a new finding is overridden in the parent POM with a
   comment, as for every other library.
3. **Why not the others.** The other mature client is a larger asynchronous library that opens and owns its sockets and
   brings a framework of its own; it would put a second network stack in the process that the address rules could not
   reach as plainly. A client written on the JDK alone would mean writing a secure shell protocol, which is not something
   to improvise. The same larger library is used **in the tests only** (test scope, never shipped) as an in-process
   server, so the tests meet a real server over a real socket.
4. **The socket is the platform's.** `EgressClient.open(endpoint, timeout)` resolves the name, requires **every** address it
   resolves to to be public (unless the host is a development host), and opens the socket to the address it checked, exactly
   as `EgressClient.call` does for the web. `SftpTransport` hands that socket to the library through its socket-factory
   hook; the library never looks a name up and never opens a connection of its own. A name that answers differently a
   moment later gains nothing.
5. **One class touches the library, and two may hold a socket.** Architecture tests (`IntegrationArchitectureTest`) fail the
   build for: any class outside `SftpTransport` that references the library; any class outside `EgressClient` and
   `SftpTransport` that references `java.net.Socket` (the old rule, extended by exactly one class); any class outside the
   worker, the poller and the tester that depends on the credential builder. The adapters keep the old purity rule
   (no `java.net`, no secrets, no database).
6. **One deadline covers a visit.** A watchdog closes the socket when the visit's time is up, whatever a blocked read is
   waiting for, and it marks the wire as timed out so the failure is classified correctly. A visit has a timeout of its own
   (the connection's `timeoutSeconds`, 5 to 120, default 30), a poll has a deadline (`platform.integration.files.deadline`),
   and a push is bounded by both.
7. **No second unguarded client.** The library's agent, proxy, port-forwarding and known-hosts features are not used; the
   session is configured with strict key checking, a single preferred authentication method, no compression and no way
   to ask anyone anything (no prompt can be answered).

## Consequences

Upgrading or replacing the library touches `SftpTransport`, the POM and nothing else. A server that only offers algorithms
the library has switched off cannot be reached, and says so as `ssh_failed`.
