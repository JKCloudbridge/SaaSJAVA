# ADR-0223: Extending the connector interface and reading the body of an answer (S28)

- **Status:** Accepted
- **Date:** 2026-10-30
- **Sprint:** S28
- **Related:** [ADR-0207](0207-the-connector-abstraction-and-the-adapter-spi.md), [ADR-0211](0211-what-sprint-26-leaves-out.md)

## Decision

`Connector` gains exactly three methods the sample `rest` connector needs: `send(settings, operation, entity)` returns an `OutboundRequest` (method, path, content type, body); `accepted(settings, operation, status)` says whether the answer shows the entity was taken (a delete of something already gone counts); `receive(settings, kind, body)` reads a payload (an incoming change, or the answer to a create) into an entity. No placeholder: all three are used by the sender and the receiver. An adapter still opens no socket, reads no secret and touches no database (architecture tests unchanged). Settings added to the `rest` type: the entities' path, the identifier field, the method for a change and the name of the credential that signs incoming changes; the description version is 2.

**Reading the body (deferred by ADR-0211).** `EgressClient.call` is the one place a body is read: by length, in chunks or until the other side closes, **never more than the caller's limit** (64 KiB for an answer by default; a larger body is `response_too_large`), under the same one deadline, address rules, certificate check and no-redirect rule as the status-only request; `204`, `304` and `HEAD` have no body. A body lives in memory only, is handed only to the adapter that reads it, and enters the platform only through mapping and `BackgroundWrites`. Sending sets the length and the content type from fixed parts; the path comes from the adapter and is checked again (`EndpointRules.validPath`); a request body is limited (256 KiB). A JSON payload is read as scalars only; nested values and nulls are left out.
