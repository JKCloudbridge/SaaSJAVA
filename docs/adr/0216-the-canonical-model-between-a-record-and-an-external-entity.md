# ADR-0216: The canonical model between a record and an external entity (S28)

- **Status:** Accepted
- **Date:** 2026-10-30
- **Sprint:** S28
- **Related:** [ADR-0207](0207-the-connector-abstraction-and-the-adapter-spi.md), [ADR-0076](0076-the-expression-engine-and-its-language.md)

## Decision

The neutral shape is `CanonicalEntity(kind, externalId, fields)` in `app.platform.integration.adapter`: the kind of entity on the external side, the identifier it has there (null before a create), and typed values by **external** field name (text, number, yes/no, date, date and time, time). It lives inside the integration module, not in the shared kernel: no other module ever needs to see an external system's shape, so nothing outside `integration` can come to depend on it.

A mapping turns a record into one and one into assignments of a record; an adapter turns one into a request and a payload into one. The model carries no permission and no record security: those are decided before it is made (the connection's scope, the record gate, the lock). It prints no value.

## Why not something richer

A model with nested objects, lists or relations would make the platform's data engine learn the shapes of other systems. The platform's fields are flat, so a flat model loses nothing; a nested payload is read only as far as its scalars, and anything nested or null is left out and never guessed at.

## Consequences

Core modules stay free of any external system's words. A second connector type changes no model; it reads and writes the same entity.
