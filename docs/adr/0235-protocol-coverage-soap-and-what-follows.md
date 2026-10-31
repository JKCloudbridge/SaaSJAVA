# ADR-0235: Protocol coverage: SOAP now, file transfer and messaging decided (S29)

- **Status:** Accepted (SOAP built; the other two decided, not built in this release)
- **Date:** 2026-10-31
- **Sprint:** S29
- **Related:** [ADR-0207](0207-the-connector-abstraction-and-the-adapter-spi.md), [ADR-0208](0208-the-integration-definition-and-the-address-rules.md), [ADR-0133](0133-event-processing-stays-on-the-outbox-decision-d3-final.md)

## Decision

1. **SOAP is built** as a connector type over the one outbound client. The adapter writes the envelope and reads the
   answer; the framework makes the call, so the address rules, the deadline, the size caps and the credential handling are
   those of every connector. Operations are named by a rule (`Create<Kind>`, `Update<Kind>`, `Delete<Kind>`) in the
   connection's namespace with the action `<prefix><operation>`. **XML from outside is read in one place** (`SoapXml`): a
   document type declaration is refused outright (no entity, no external fetch), secure processing is on, external access
   is off for every protocol, the number of elements and their depth are bounded, only leaf elements are read, and a
   refusal never repeats the document. Out of scope: reading a service description, message security, attachments, SOAP 1.2.
2. **File transfer (SFTP), pull and push, is decided and not built.** It needs a library (the JDK has no SSH) and a second
   transport in the worker. To be held to the same rules it would: resolve and check every address with `HostAddresses` and
   `EndpointRules` in our own code and hand the library a socket already opened to the checked address (no second unguarded
   client); pin the host key in the connection's settings (no trust on first use); take the key or password as a named
   credential like any other (`SecretValue.reveal()` extended by exactly the one class); read files with the counted,
   no-entity readers of the spreadsheet upload; claim a file by renaming it so it is never processed twice; write through
   `BackgroundWrites` with the source `INTEGRATION`. Development hosts are the existing, `prod`-refused setting.
3. **Messaging is decided and not built:** an outbox-backed contract (the delivery row is the outbox) with one simulated
   broker, so the exit never depends on a product. D3 (ADR-0133) stays: the outbox plus a database queue first, a real
   broker only when a customer needs it.
4. **The seam for both is the same:** a transport returns the same `Answer` the HTTP client does (a status or a stable
   failure), so classification, retries, the breaker, the limits, the log and the links apply unchanged. That is why they were
   left for a release of their own rather than half-built.

## Consequences

A need for SFTP or a broker cannot be expressed today and is never half-supported (ADR-0211's rule).
