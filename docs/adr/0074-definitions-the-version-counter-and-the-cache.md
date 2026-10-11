# ADR-0074: Definitions, the version counter and the cache; abilities

- **Status:** Accepted
- **Date:** 2026-10-11
- **Sprint:** S12
- **Related:** [ADR-0053](0053-the-security-version-and-the-permission-cache.md), [ADR-0061](0061-the-metadata-version-and-the-catalogue-cache.md), [ADR-0066](0066-change-sets-and-the-publication-of-metadata.md), [ADR-0068](0068-the-publish-ability-and-changes-made-at-once.md)

## Decision

1. **Only published metadata reaches the cache, with the same version counter** (recommendation of the sprint prompt, accepted). The four new tables carry the trigger `<table>_metadata_version` of
   [ADR-0061](0061-the-metadata-version-and-the-catalogue-cache.md), so every change of a layout, record page, application or assignment raises the organization's metadata version in the transaction of
   the change. The snapshot (`MetadataSnapshot`) now also holds the layouts, record pages, assignments and applications, so a change is visible to the next question on every instance, the cache is
   still only a speed-up, and a transaction that has changed definitions reads its own changes and never fills the cache. A draft is a row of the change set tables and touches none of this.
2. **No security version.** A definition does not decide what a member may do. The member's profile is read per request (`MemberAccess.profileOf`), never cached with the definitions, so the
   security cache ([ADR-0053](0053-the-security-version-and-the-permission-cache.md)) has nothing to learn and `SecurityVersionIT` does not change.
3. **Memory.** Definitions are in the snapshot, so a large definition costs memory in every cached organization. Bounds: at most 50 layouts and 200 assignments per object, 20 record pages per object,
   20 applications per organization (`platform.metadata.limits.*`), each definition at most 200,000 characters (database check) with the schema's own bounds (20 sections, 100 fields per section,
   30 components, 50 navigation items). The cache already bounds the number of organizations kept.
4. **Abilities (decided with the product owner's recommendation): no new ability.** `metadata.view` reads layouts, record pages, applications and assignments; `metadata.manage` drafts them in a
   change set; `metadata.publish` puts a change set live and rolls back; a change made at once needs `metadata.manage` **and** `metadata.publish` ([ADR-0068](0068-the-publish-ability-and-changes-made-at-once.md)).
   The member's own menu (`GET /api/v1/applications`) needs no ability: any active member reads their own.
5. **Audit.** Every step is recorded by names and counts, never by what was typed: `metadata.layout.created|updated|deleted`, `metadata.recordpage.*`, `metadata.application.*`,
   `metadata.layoutassignment.*` (object and name as the key, the kinds of change as `changed`, the number of sections, components or items as `size`), next to the release records of the lifecycle.
   They fall under the audience rule for `metadata.` records (the organization).

## Consequences

- `TenantScopedTables` lists the four tables, so `TenantIsolationIT` covers them; every endpoint has the usual allowed, denied, cross-tenant, forged-header, platform-administrator and
  unauthenticated tests (`DefinitionsAccessIT`).
