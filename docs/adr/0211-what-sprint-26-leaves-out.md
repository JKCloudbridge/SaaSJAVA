# ADR-0211: What Sprint 26 leaves out, and why

- **Status:** Accepted
- **Date:** 2026-10-30
- **Sprint:** S26
- **Related:** [ADR-0166](0166-what-sprint-25-leaves-out-and-why.md), [ADR-0207](0207-the-connector-abstraction-and-the-adapter-spi.md) to [ADR-0210](0210-the-connection-lifecycle-and-connection-testing.md)

Written down so that nobody reads an omission as an oversight.

| Left out | Why | Where it comes |
|----------|-----|----------------|
| **A real secrets store, creating or rotating a credential** | decision D4 puts the implementation in Sprint 27; Sprint 26 stores only a reference, so a connection that needs a credential cannot be tested in a deployment until then (reason `credential_store_unavailable`) | Sprint 27 |
| **Mapping, the canonical model, external identifiers** | the safe expression engine is used there; nothing in Sprint 26 reads a record | Sprint 28 |
| **Sending a record, receiving one, the receiver endpoint and its signature check** | nothing external may be sent before mapping and the secrets exist; `inbound_id` is in the schema so the receiver needs no contract migration (ADR-0209) | Sprint 28 |
| **Retries, circuit breakers, per-connection rate limits, a queue, dead letters, other protocols (SOAP, file transfer, messaging)** | one test is rate limited by the database clock; everything about repeated or queued calls belongs with the reliability work | Sprint 29 |
| **A runtime report of failure** (`REPORT_FAILURE`) | the machine has the transition; no code that uses a connection exists to report one | Sprint 29 |
| **Per-field and per-record scope of an integration** | exercised only when a record is read or written for a connection | Sprint 28 |
| **A public API, keys, machine OAuth clients, webhooks to others** | other milestones (M8); no second login mechanism is invented here (ADR-0006) | Sprints 30 to 32 |
| **The definition as metadata (change sets, releases, rollback)** | an address and a credential reference are environment-specific and a rollback must not move where records go (ADR-0208) | reversible by decision, at the price of one change-kind family |
| **A designer for events and scope, a log of tests, a monitoring screen** | a plain form is enough to define, test and switch on; the last test is kept on the row | Sprints 28 and 29 |
| **An outbound proxy, an allow-list of external hosts per organization** | the address rules and the resolved-address check are the defence; a deployment may add a proxy in front | on request |
| **Reading the body of an answer** | a test needs a status; a body would be a place for another system's data to enter a log or a response | Sprint 28, where mapping needs it |
| **Asynchronous tests** | an administrator waits for the answer; the call is bounded to 10 seconds | Sprint 29 |
| **A second sample connector, or a catalogue of named external products** | the core names none; the sample proves the seam | never in the core |
| **HTTPS end to end in the automated integration tests** | the TLS rules are tested against a local server with a generated certificate (`EgressClientTest`); the end-to-end tests use plain HTTP to a development host, which the test profile names | covered at the unit level |

A definition that needs one of these rows cannot be expressed; it is never half-supported.
