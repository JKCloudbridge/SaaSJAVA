# ADR-0228: Repeat-safe sends and the extra headers of an adapter (S29)

- **Status:** Accepted
- **Date:** 2026-10-31
- **Sprint:** S29
- **Related:** [ADR-0227](0227-retry-classification-and-backoff-for-sends.md), [ADR-0223](0223-extending-the-connector-interface-and-reading-the-body-of-an-answer.md)

## Context

Once a send is retried, an answer that was lost after the effect happened would make a second entity. The platform must
say what it can and cannot promise.

## Decision

1. **One logical send has one key:** `<delivery id>:<generation>`, written to the row at the first attempt and the same on
   every attempt of that generation. A replay by an administrator (ADR-0231) is a new generation and so a new logical send.
2. **The adapter names the header that carries it** (`Connector.idempotencyHeader`, null by default). The framework fills
   it; the REST connector uses `Idempotency-Key`, the SOAP connector uses the header its connection names.
3. **What the platform promises:** with a service that honours the key, a create whose answer was lost makes one entity
   however many times it is repeated. With a service that does not, a create whose answer was lost *may* make a second
   entity; the platform cannot check for it, because the link is written only from the answer. It promises no more, and the
   runbook says so. An update and a removal are safe to repeat by their nature.
4. **A completed effect is never repeated:** the operation of every attempt is derived from the link (a record that has
   one is updated, never created), so a replay after a success on the other side changes instead of creating.
5. **Extra headers are checked.** An adapter may add a few headers its kind of system needs (`OutboundRequest.headers`,
   for example the action of a SOAP envelope). `ExtraHeaders` refuses any the framework sets itself, any that could carry
   a credential or reroute a request, a name or value that is not plain, and more than eight. The framework adds the
   credential itself, last.

## Consequences

The fake external system of the tests honours the key and counts what really took effect on its side
(`FakeService.effects()`), so "sent twice" and "done twice" can be told apart (`RetryClassificationIT`).
