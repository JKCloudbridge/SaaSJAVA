# ADR-0178: Shares to roles

- **Status:** Accepted (built in FA2B; what the build settled is in [ADR-0185](0185-shares-to-roles-the-typed-grantee-and-the-choice-of-roles-and-groups.md))
- **Date:** 2026-10-21
- **Sprint:** FA0 (Functional track, spike; no product code)
- **Related:** [ADR-0042](0042-the-role-hierarchy.md), [ADR-0115](0115-the-record-access-model-and-how-it-reaches-a-statement.md), [ADR-0116](0116-defaults-hierarchy-and-sharing-rules.md), [ADR-0117](0117-explicit-sharing-and-who-may-share.md), [ADR-0118](0118-view-all-modify-all-and-one-evaluation.md), [ADR-0053](0053-the-security-cache-and-its-version.md), [ADR-0174](0174-template-versions-and-approval-together.md)

## Context

An approved template is **published** to people, groups or **roles**; people who were not given it do not see it, and publishing is separate from approving. Explicit shares exist for a **user** or a **public group** only (`record_share` has the database check `grantee_type in ('USER','GROUP')`, V045).
A role can be the target of a **sharing rule** (criteria-based), but not of a share. Two ways: extend the explicit share with `ROLE` and `ROLE_AND_SUBORDINATES`, **or** publish through generated sharing rules.

## Decision

**Extend the explicit share with `ROLE` and `ROLE_AND_SUBORDINATES`.** Publishing a template is a set of shares on its **header** record at the **read** level; unpublishing deletes them.

### How it is built

- **Database (FA2):** the check becomes `grantee_type in ('USER','GROUP','ROLE','ROLE_AND_SUBORDINATES')`; the unique index `(tenant_id, record_id, grantee_id)` stays (one share of a record per grantee, so the same role cannot be shared both ways at once: sharing again changes the kind and level).
- **The resolver:** `RecordAccessResolver.Facts` carries today `grantees` (the user and every public group the member is in) and `subordinates`. It gains `role` (the member's own role) and `roleChain` (that role and every role **above** it).
  The admission tree's `SharedWith` is extended so a share matches by **type**: a `ROLE` share matches the member's **own** role; a `ROLE_AND_SUBORDINATES` share matches **any role in the member's own chain** (the share is on role X, the member is in X or below X, so X is in their chain);
  a user or group share matches as today. The statement builder (`AdmissionPlans`, `ShareStore`) joins on `(grantee_type, grantee_id)`, never on the identifier alone (identifiers of roles, groups and users are in different spaces but the match must not depend on that). `Decisions.can(member, object, action, RecordRef)` asks `RecordMatcher`, which `data` implements **with the same statement builder**, so there is still no second Java rule ([ADR-0115](0115-the-record-access-model-and-how-it-reaches-a-statement.md)).
- **The share panel and endpoints:** `POST /records/{id}/shares` accepts the two new kinds (the role named by its API identifier from the role list; a role the caller cannot see is "not found"); the panel (`SharePanel.tsx`) offers "A role" and "A role and the roles below it" beside person and group. The panel and the endpoint already name people, never identifiers.
- **Limits:** the existing `platform.data.sharing.max-shares-per-record` (100) holds; a publication to many audiences uses roles and groups, not people.

### The comparison the prompt asked for

| | Explicit share extended (chosen) | Publish through generated sharing rules |
|---|---|---|
| **Who may see the record afterwards** | members of the role (or of it and below), plus whoever could already (owner, the object's default, hierarchy, other shares); read only | the same, but only through the rule's **criterion**: a rule shares records that *meet a condition* with a role, so "published to role R" needs a field on the header naming the audience and one rule per role |
| **Unpublish** | delete the shares: effective at once (shares are read live) | change the audience field; the rule drops it for everyone at once, but **only if no other record uses the rule** (a rule is per role, shared by every template) |
| **Security version and caches** | explicit shares are **read live and raise no version** ([ADR-0117](0117-explicit-sharing-and-who-may-share.md)): publish and unpublish take effect on the next question on every instance. A *member's role change* already raises the version (the trigger on `member_access`) | a rule is metadata of sharing: creating or changing one **raises the security version** and recomputes the owner sets; the cost of every publish lands on every member's cache |
| **Count limits** | 100 shares a record (settings) | **50 rules per object** (`max-rules-per-object`): one rule per audience role, created by a *publish*, would be a metadata change from inside a business action, subject to the change-set lifecycle and its permissions |
| **Oracle and property tests** | `RecordAccessResolverTest`, `RecordAccessPropertyTest` and the differential oracle get the two kinds and the role chain; an **independent Java oracle** for "who may read" is extended (the tests already hold the statement to it) | no new resolver code, but a new class of surprises (a rule that outlives its template, a rule the administrator edits) |
| **Cost** | a migration (the check), the resolver, the statement builder, the matcher, the panel, the tests | the cluster creates and deletes rules; the criterion model compares a field with a value (not "contains" a role) so the audience would be one field per role |
| **Visible to the person publishing** | exactly "who I gave it to" | "rules" they did not write |

### What must hold (the oracle tests of FA2)

1. A member in role X reads a record shared `ROLE` with X; a member in a role **below** X does not; a member in a role above X **does** read it through the hierarchy only if the object grants access by hierarchy (as today).
2. `ROLE_AND_SUBORDINATES` on X is read by members of X and every role below it, and by nobody else.
3. A share to a role never gives more than **read** for a publication (the level is chosen by the publisher; the cluster uses read), and never lets the member **delete** (shares never give delete: [ADR-0118](0118-view-all-modify-all-and-one-evaluation.md)).
4. Unshare removes it on the next question; a member moved out of the role loses it on the next question (the role change raises the version).
5. Object permission is asked first; a share never gives a record the member's object permission does not allow.
6. **A draft never leaks**: the share is on the header, which holds no grid; the version endpoint decides the version's visibility by status ([ADR-0174](0174-template-versions-and-approval-together.md)). A cross-tenant test and a forged-header test as for every share endpoint.

## Consequences

- FA2 does it; FA4 uses it (publish and unpublish are two endpoints that create and delete the shares in one transaction with the version status change).
- The share kinds are visible in the audit trail by kind and counts, never by a person's name or the role's label.
- If a later cluster needs "everyone with this profile" or "everyone in a licence type", it is a new grantee kind with the same shape; nothing here closes that.
