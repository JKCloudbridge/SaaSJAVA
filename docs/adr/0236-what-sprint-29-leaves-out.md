# ADR-0236: What Sprint 29 leaves out, and why (S29)

- **Status:** Accepted
- **Date:** 2026-10-31
- **Sprint:** S29
- **Related:** [ADR-0224](0224-what-sprint-28-leaves-out.md)

| Left out | Why | Where it comes |
|----------|-----|----------------|
| **SFTP and file-based flows (pull and push)** | needs a library and a second transport; decided in ADR-0235 | next release of M7 |
| **A messaging adapter (the contract and the simulated broker)** | same seam as above | next release of M7 |
| **A failure rate over a window for the breaker** | consecutive failures are simple to explain and test (ADR-0229) | on request |
| **A platform support role forcing the breaker or replaying** | organization abilities only; support access has no door to integration settings (ADR-0209) | not planned |
| **A dedicated platform endpoint for integration health** | the platform automation health already carries the integration counts without a name (`IntegrationHealth`) | on request |
| **Retrying a receive** | the sender of a change retries; the receiver answers once | Sprint 32 for webhooks |
| **Per-field or per-operation retry choices** | one policy per platform, one limit per connection | on request |
| **Ordering across records** | only within one record (ADR-0230) | not planned |
| **Reading WSDL, SOAP message security, attachments** | ADR-0235 | on request |

A need that fits one of these rows cannot be expressed today; it is never half-supported.
