# ADR-0179: Packaging: the whole organization, by plan feature only

- **Status:** Proposed
- **Date:** 2026-10-21
- **Sprint:** FA0 (Functional track, spike; no product code)
- **Related:** [ADR-0046](0046-licence-kinds-and-the-licence-rule-of-access-policies.md), [ADR-0152](0152-entitlement-abilities-visibility-and-telling-the-approver.md), [ADR-0175](0175-how-a-platform-shipped-application-exists.md), [ADR-0174](0174-template-versions-and-approval-together.md)

## Decision (the product owner's, 2026-10-21; recorded here in a few lines)

The cluster is sold to the **whole organization** through one **plan feature** (`inspection`, a key in the `feature` table, set on a plan and overridable per organization). There is **no add-on licence type** and **no per-seat charge**; the shipped access policies are **not licence-bound** ([ADR-0046](0046-licence-kinds-and-the-licence-rule-of-access-policies.md)),
so they use no seat. **Licence, feature and permission stay three separate mechanisms**: the feature says whether the organization may use the cluster, the access policies say what a person may do inside it.

## In plain words

- **What the platform administrator clicks:** in the console, add the feature `inspection` to a plan (or give an organization an entitlement override). Nothing else: no licence quantity, no pool. (FA1 adds the feature row by migration and shows it in the plan and organization screens.)
- **What the organization's administrator then does:** the shipped access policies (Author, Approver, Reader, Filler, in FA1 and FA4, **once the roles table of the business is filled in**) appear in the organization by themselves; the administrator assigns them to people or groups, and builds the approval process in `/setup/approvals` and the roles of the organization. Until a person holds a policy they see nothing of the cluster.
- **When the feature is removed:** the application and the shipped objects are hidden at once ([ADR-0175](0175-how-a-platform-shipped-application-exists.md)); **nothing is deleted**; the data, the approvals in flight and the policies stay; restoring the feature brings everything back. The platform administrator's confirmation says this.
- **The trial:** a plan used for a trial may include the feature; the existing trial rules apply (the end of a trial suspends the organization, never deletes).

## Consequences

- No licence-type work and no per-seat screens in FA1. If a per-seat sale is wanted later, an add-on licence type bound to the policies is the existing mechanism (ADR-0046) and does not change the model.
- The plan feature is asked with `Entitlements.enabled("inspection")` in the one place that gates the cluster (the loader and the object lookup of ADR-0175), never scattered.
- Roles table: still empty in the business file; **needed before FA4** ([ADR-0178](0178-shares-to-roles.md) publishes to roles; the Approver policy needs the override ability: [ADR-0174](0174-template-versions-and-approval-together.md)).
