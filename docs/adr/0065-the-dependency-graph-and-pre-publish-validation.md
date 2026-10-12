# ADR-0065: The dependency graph and pre-publish validation

- **Status:** Accepted
- **Date:** 2026-10-10
- **Sprint:** S11
- **Related:** [ADR-0062](0062-removing-objects-and-fields.md) point 6, [ADR-0064](0064-record-types.md), [ADR-0066](0066-change-sets-and-the-publication-of-metadata.md); architecture note 3 sections 22 and 23

## Context

ADR-0062 promised a dependency check in `MetadataService.deleteObject` and `deleteField`. The exit criterion of Sprint 11: an invalid dependency blocks publication
with a precise error. The sample in the plan is "a field deleted while referenced by a layout or rule", but layouts arrive in Sprint 12 and rules in Sprint 13.

## Decision

1. **One graph of published metadata** (`DependencyGraph`), built from a snapshot of the catalogue and the record types. A node is an object, a field, a record type or a
   picklist value (named, never printed). An edge says "this needs that" with a verb ("points to", "offers", "restricts the picklist", "allows").
2. **Edges come from contributors**, one bean per kind of dependent (`DependencyGraph.Contributor`). Sprint 11 has two: `Dependencies.References` (a lookup or
   master-detail needs its target object) and `Dependencies.RecordTypes` (a record type needs its object, every field it offers, every picklist it restricts and every
   value it allows; plus the rule that a restricted record type offers every required and master-detail field). Layouts (S12) and rules (S13) add a contributor each;
   nothing else changes. Formula text is **not parsed** before Sprint 13, so a formula's references are not edges yet (stated in the sprint document).
3. **The question is only ever "does everything that is needed still exist?"** asked of a snapshot. A violation names the dependent precisely:
   *"record type NewBusiness__c of Deal__c offers field Deal__c.department__c, which does not exist after this change."* The same graph answers "what depends on this?".
4. **Where it runs.** After a change has been applied inside its transaction, the graph of the result is checked; a violation stops everything and rolls the
   transaction back:
   - a **change made at once** (a live endpoint) is checked after the one change, and a violation is a CONFLICT whose `fields.dependencies` lists every dependent;
   - a **change set** is checked after all its changes, so a set may remove a field and update the record type that offered it, and the end state is what counts;
   - a **rollback** is checked the same way.
   This wires the check into object and field removal (ADR-0062 point 6) without a second implementation: `MetadataService` keeps its own rules (an object that other fields point
   to cannot be removed), the lifecycle adds the graph.
5. **The graph is built from the same snapshot the runtime reads** (published metadata), never from a draft: a draft is applied inside a rolled-back transaction ([ADR-0066](0066-change-sets-and-the-publication-of-metadata.md)), so the check sees the world as it would be.

## Consequences

- The exit criterion is proven with a lookup and a record type (`RecordTypesIT`, `ChangeSetLifecycleIT`) and the smoke script, and the contributor interface proves
  the extension point in `DependencyGraphTest`.
- A bug found on the way by the static analysis: a boxed `Long` compared with `==`; fixed.