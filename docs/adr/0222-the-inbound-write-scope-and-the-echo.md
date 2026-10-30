# ADR-0222: The inbound write, its scope and the echo (S28)

- **Status:** Accepted
- **Date:** 2026-10-30
- **Sprint:** S28
- **Related:** [ADR-0139](0139-background-writes-the-one-reviewed-path.md), [ADR-0140](0140-loop-and-recursion-protection.md), [ADR-0209](0209-integration-identity-permissions-and-tenant-isolation.md)

## Decision

A change that came in is written by `InboundService` through `BackgroundWrites` with the new source kind `INTEGRATION`, the fourth reviewed caller; an architecture test lets only `InboundService` of the integration module name it. It runs the whole write pipeline (shape, type, required, relationships, unique, rules, formulas) and the record lock; it skips only what is about **who** (member gate, object and field permission, record security). The actor is the platform, but its identity for scope is the **connection**: `Integrations.allows(connection, object, CREATE|UPDATE)` is asked first and a refusal is the reason `out_of_scope`, never the creator's rights (ADR-0209). The scope is also checked at publish.

A refusal of the pipeline is a stable reason `write_<reason>`; a pending approval gives `write_record_locked` (409). The write runs in a nested transaction so a refusal does not undo the receipt of the nonce, and the delivery records the outcome. Records made from outside are owned by the person who published the mapping.

**The echo.** The event of an inbound write carries the connection in its chain (`Source.integration`, depth 1, the connection as the single `workflows` entry, the change as the root). `OutboundHandler` skips a connection found in the chain, recording a delivery as `SKIPPED` with reason `echo`; another connection that listens still gets the change. Workflows started by that event see the chain as for any background write, so a workflow it starts is not stopped and its writes are sent on (the chain gives the loop protection of ADR-0140, no hashes are compared).

A change that sets nothing the platform does not already hold (the hash of the assignments equals the link's) is skipped as `unchanged`.
