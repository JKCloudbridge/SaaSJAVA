# ADR-0217: Where a mapping lives and its lifecycle (S28)

- **Status:** Accepted
- **Date:** 2026-10-30
- **Sprint:** S28
- **Related:** [ADR-0208](0208-the-integration-definition-and-the-address-rules.md), [ADR-0063](0063-relationships.md)

## Decision

A mapping is one object of one connection (`integration_mapping`, unique per connection and object and per connection and entity) with immutable numbered versions (`integration_mapping_version`: `DRAFT`, `LIVE`, `SUPERSEDED`). It is **not** metadata and has no change sets or releases, for the reason of ADR-0208: it belongs to an environment, like the connection it hangs on, and must not travel with a release or a rollback.

- One draft and one live version at a time (partial unique indexes). The draft is saved with the version read; it does nothing until it is published.
- **Publishing** checks the draft again against the object as it is now and against the connection's scope (an outgoing field needs read; incoming fields need create or update as the mapping allows), supersedes the live version and promotes the draft. Abilities `mappings.view` and `mappings.manage`, apart from `integrations.*`; the plan feature stays `integrations`.
- **What was published never changes** and is never removed: the database refuses it as the application role and as the owner (V085). A send or receive is bound to the version live when it started (`mapping_version_id` on its delivery), so a publish in between changes the next event, never one in flight.
- A connection that is on is **not** frozen by a mapping change: the connection's own definition is frozen, the mapping is the thing that is changed under it, and a new version applies from the next event. Stopping a mapping ends the live version; nothing is sent or received for the object until a draft is published again.
- A field or object a **live** mapping uses cannot be removed: `MappingUsage` (shared kernel, implemented by integration) is asked by `MetadataService` like `ObjectUsage` and `ApprovalUsage`, with no new module edge. A draft is not an edge; it is checked again at publish.
- The definition is text of the organization: checked, stored, never logged, audited or put in a metric; audit carries identifiers and the number of fields.

## Consequences

An administrator can try a definition without risk (dry run), publish it, and go back by publishing the earlier text as a new version; the history shows every live version.
