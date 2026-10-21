# ADR-0175: How a platform-shipped application exists

- **Status:** Proposed
- **Date:** 2026-10-21
- **Sprint:** FA0 (Functional track, spike; no product code)
- **Related:** [ADR-0073](0073-applications-navigation-and-the-members-menu.md) (applications and the member's menu), [ADR-0128](0128-tabs-and-security-aware-navigation.md), [ADR-0059](0059-standard-metadata-in-definition-files.md) (standard objects as files), [ADR-0045](0045-system-profiles-seeding-backfill-and-licence-consequences.md) (system profiles ensured per organization), [ADR-0152](0152-entitlement-abilities-visibility-and-telling-the-approver.md), [ADR-0179](0179-packaging-the-whole-organization-by-plan-feature.md)

## Context

Applications, tabs and custom pages are **rows of the organization** today (`application`, `tab`, `custom_page`); `MyApplications` builds the member's menu from `metadata.applications()` and filters by the application's profile list and the member's read permission on the objects.
Nothing ships an application to every organization, and nothing gates a menu entry on a plan feature. The cluster is sold by a plan feature ([ADR-0179](0179-packaging-the-whole-organization-by-plan-feature.md)) and must appear for an organization that has it and for no other.
Two ways: **computed** (the shipped application is merged into `GET /api/v1/applications` by code, with no organization rows) or **ensured** (rows are written into each organization, as `MemberAccess.ensureSystemProfiles` does for profiles).

## Decision

**Computed.** A new module `solution` loads read-only bundles shipped inside the application (`resources/solutions/<key>/solution.yml`: key, label, the plan feature, the application and its entries, the shipped objects) and answers the shared-kernel contract **`ShippedApplications`**
(`for(member) -> List<ShippedApplicationView>`, a small **neutral view type defined in the shared kernel**, because the API type `MyApplicationView` lives in `platform-api-contract`, which the shared kernel does not depend on; `MyApplications` maps the neutral type to the API type); `MyApplications` merges the answer through an `ObjectProvider<ShippedApplications>` (the inversion of `RecordLocks`: `application` gets no edge to `solution`, `solution` depends on `licensing`, `metadata` and `security`).
The gate is one question, `Entitlements.enabled(feature)`, asked per request, never stored. Its entries are `OBJECT` entries (judged exactly like an organization's, by the member's read permission) and a new entry type **`PLATFORM`** naming a page key that the web registry knows
(`platform-web/src/solutions/<key>/registry.ts`, rendered by the existing `/apps/[application]/[entry]` route). **No tenant row is written for the application, its tabs or its pages.**

**The access policies are different and are ensured** (rows, per organization, `managedBy: solution`), because a policy is *assigned to people* and referenced by member rows: it is the organization's data, where an application is only a view. They are made the way system profiles are, in the same transaction,
at the first use after the feature is gained, at subscription change and at start; an organization that loses the feature keeps the rows (members keep what they were given) but the objects they name are hidden (below).

**The shipped objects are platform definitions like the standard objects** (YAML, the loader, the baseline test, never deleted or renamed: [ADR-0059](0059-standard-metadata-in-definition-files.md)) with one new attribute, `requiresFeature`. The metadata lookups (`Metadata.object(...)`, `objects()`, and `ObjectCatalog`) leave an object out when the organization lacks that feature,
**evaluated when asked, not baked into the metadata snapshot** (the snapshot is keyed by the metadata version, which an entitlement change does not move). Every other module asks `Metadata`, so an unentitled object answers "not found" everywhere, exactly like an unknown object (the existing rule of the record gate).

### Comparison

| | Computed (chosen) | Ensured rows |
|---|---|---|
| Code | the loader, the gate, one contract, one new entry type, one registry in the web app | an ensure job (made on feature gain, on subscription change, at start), an upgrade job, protection against edits, a feature filter in `MyApplications` as well |
| Drift | none: nothing is copied | a tenant can edit or reorder unless blocked; an upgrade can half-apply across tenants |
| A new version of the bundle | reaches every organization with the deploy, atomically per instance | a per-tenant job over every tenant, with partial failures and a window where tenants differ |
| Change sets, releases, rollback, the dependency graph | do not see it (a release cannot roll a shipped application back, which is right) | see it: a rollback or a publish can touch what must not be touched |
| An organization loses the feature | the application and its objects vanish at once; **nothing to clean** | rows stay, so the gate is needed anyway |
| Tests | a gate matrix (feature on or off, profile, read right), "no tenant row exists", "an organization without the feature sees nothing and cannot read the object" | the same, plus drift and upgrade tests |
| What an organization administrator can customise | nothing of the shipped application (rule 5 of the track: nothing in a shipped bundle can be edited or removed); they build their own applications as before | could be allowed, by accident |
| Where it costs more | the shipped application is not visible in the object manager's application list (a later screen may list "Shipped by the platform") | the object manager shows it for free |

## What happens to an organization that loses the feature

The menu entries disappear on the next request; the shipped objects answer "not found"; the data is **kept** and comes back when the feature is restored. A pending approval of a template stays pending (ADR-0152: a request finishes after a feature loss) but its record is not
reachable by the approver until the feature returns; the platform administrator is told this on the confirmation that removes the feature from a plan. The shipped policies stay in place and are harmless (they name hidden objects).

## Consequences

- FA1 builds: the `solution` module (loader, gate, `ShippedApplications`), the `PLATFORM` entry type (API contract, OpenAPI, generated client, web registry), `requiresFeature` on object definitions and its evaluation at lookup, the ensured policies, the architecture test that keeps cluster words out of generic modules, and the loader's refusal of a bundle that names an unknown feature or object.
- A `PLATFORM` entry never carries a permission of its own: the page it names asks the API, and the API decides every request again (the standing rule that the browser only presents).
- The decision applies to every later cluster; it is the reason the cluster's own module is separate from `solution`.
