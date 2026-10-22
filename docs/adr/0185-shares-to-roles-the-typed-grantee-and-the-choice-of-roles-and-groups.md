# ADR-0185: Shares to roles: the typed grantee, the roles and groups to choose from, and what happens when one is removed

- **Status:** Accepted (the product owner delegated the technical choices on 2026-10-22 and answered the product questions: see "How the first use sets it up")
- **Date:** 2026-10-22
- **Sprint:** FA2B (Functional track)
- **Related:** [ADR-0178](0178-shares-to-roles.md) (the decision this builds), [ADR-0042](0042-the-role-hierarchy.md), [ADR-0053](0053-the-security-cache-and-its-version.md), [ADR-0115](0115-the-record-access-model-and-how-it-reaches-a-statement.md), [ADR-0116](0116-defaults-hierarchy-and-sharing-rules.md), [ADR-0117](0117-explicit-sharing-and-who-may-share.md), [ADR-0118](0118-view-all-modify-all-and-one-evaluation.md), [ADR-0125](0125-record-level-security-on-writes-links-and-uniqueness.md), [ADR-0148](0148-record-locking-while-an-approval-is-pending.md)

## Context

[ADR-0178](0178-shares-to-roles.md) decided to extend the explicit share of a record with two kinds, `ROLE` (the members whose own role it is) and `ROLE_AND_SUBORDINATES` (the members of that role and of every role below it), and to match a share by kind and identifier in the one statement. Building it settled six points the ADR left to the sprint: how far the typing goes, who may pick a role or a group, what a removed or moved role does, what "cannot see a role" means, how the existing unshare route behaves, and what the new condition costs.

## Decision

### 1. The grantee is typed from the resolver to the statement

`Grantee(kind, id)` (security root, public) replaces the bare identifier. `RecordAccessResolver.Facts` carries the member's `groups`, own `role` and `roleChain` (the role and every role above it) instead of one mixed set of identifiers; the resolver turns them into the pairs the member **is** for a share: their user, each group, their own role as `ROLE`, and each role of the chain as `ROLE_AND_SUBORDINATES`. `Admission.SharedWith` holds those pairs; `QueryPlan.SharedWithAny` holds `(type, id)` pairs; the statement matches a stored share when **both** equal. Nothing in `data` knows what a role is or which role a member holds: the meaning of the kinds is written once, in `Grantee` and in the resolver, and the oracle in the tests restates it independently by walking a fixed role tree.
`DefaultRecordSecurity` computes the role chain once per description and reuses it for the sharing rules aimed at "a role and its subordinates" (it was computed only when such a rule existed; it is now one recursive read per cache miss on a Private or Public Read object). The list, the query, the single read, the lookups and `Decisions.can(..., RecordRef)` still run the same builder: no second rule.

### 2. The statement, and what it costs (measured)

One condition: `id = any(array(select record_id from record_share where tenant, object, live, grantee_id = any(all identifiers) and (one branch per kind: grantee_type = $kind and grantee_id = any(ids of that kind)) and level))`. The first, kind-free test on the identifier is a coarse filter that the existing index (`record_share_tenant_grantee`) serves; the branches then apply the exact match. Identifiers of users, groups and roles never collide, so the coarse test removes nothing the branches would keep, and without it the planner chose a scan of the share table (about twice as slow in the measure below). **No index was added** (an index with the kind in it was measured and did not change the plan or the time).

Measured on PostgreSQL 18.6, a reduced copy of the two tables with their indexes, 20,000 records of one organization among 80,000, one list page of 51 (a repeatable script is in `benchmark/role-shares/`; no threshold of any benchmark was changed):

| | before the change (identifier only) | after, same data | note |
|---|---|---|---|
| 2,000 shares to people, none to roles | 0.18 ms | 0.28 ms | the member is now addressed by 9 pairs instead of 3; about 0.1 ms more |
| plus 6,000 shares to roles, the member's chain matching about 450 of them | 0.18 ms (the member could not see them) | 0.75 ms | the member now **reaches 450 more records**; the work is that of reading them (the bitmap over 31 index searches), not of the shape |
| the same with branches only, no coarse test | | 1.5 ms | why the coarse test stays |

In plain words: a list on an organization that uses no role shares costs about a tenth of a millisecond more on this data; a list for a member who reaches hundreds of records through a role costs what reaching them costs, under a millisecond here. It is not free, and nothing near a limit.

### 3. Sharing the same role again changes the kind

The unique index `(tenant_id, record_id, grantee_id)` stays, so a role can be shared once. Sharing it again changes the level **and the kind** (`ROLE` to `ROLE_AND_SUBORDINATES` and back). ADR-0178 said only "changes the level"; the guard trigger of V045 forbade any change of `grantee_type` and the upsert did not set it. Migration V063 (a) replaces the check by `grantee_type in ('USER','GROUP','ROLE','ROLE_AND_SUBORDINATES')`, added `not valid`, validated, then the old one dropped, and (b) lets the guard allow exactly one change of kind: between the two role kinds for the same role. A share still never moves to another record or grantee. No table is added: no new row level security and no isolation entry; the security version is still not raised by a share.

### 4. Who may pick a role or a group: a read under the sharing check

`GET /api/v1/objects/{object}/records/{id}/share-targets?kind=ROLE|GROUP&search=` answers names and identifiers (`ShareTargetView`: `id`, `label`) of the live roles or public groups whose name contains the text, **at most 25**, by name; a search text over 80 characters or another kind is a validation error. It runs under the **same check as sharing** (the right to change the record, a lock aside): a member who can read the record but not change it is forbidden, one who cannot reach it is told it does not exist, another organization's caller gets the same answer. It says nothing about the people in a role or group and needs no ability: the members who may share a record (its owner, a holder of edit or modify-all) seldom hold a setup ability, and the role and group endpoints are setup endpoints. The security module publishes the names through a small contract, `ShareTargets` (`roleExists`, `roles`, `groups`, `roleLabels`, `groupLabels`); `data` uses it and reads no role or group table.

### 5. What "cannot see a role" means

Roles are the organization's setup, not secrets. A caller who may share a record may name **any live role of their own organization**. "Cannot see" therefore means "is not a live role of this organization", which row level security and the live-row filter already make identical for a role of another organization, a removed role and an identifier that never existed. Sharing with any of the three answers a validation error on the grantee with the same words ("Is not a role of this organization.") and sharing is not tried for a role "seen but not allowed".

### 6. A removed role or group ends the shares to it, in the same transaction

`GranteeRemovals` (security root) is told by `RoleService.delete` and `GroupService.delete` in their transaction; `data` implements it and deletes the shares addressed to that role (both kinds) or group. The audit record of the removal carries the **number** ended (`shares_ended`), never a name of a share. The removal was already refused while the role has members, sub-roles or a sharing rule, so a share to a removed role could never have matched anyone (the chain of a member holds live roles only): ending it changes no answer, it only keeps the list of shares and the limit of 100 free of rows that name nothing. A share made in the instant a role is removed by another request can still dangle; it matches nobody, and the share panel shows it as "A role that no longer exists" with a Remove button. Groups behaved this way before (a removed group left its shares); the same hook now ends them.

### 7. A moved role, a member who moves

Moving a role in the tree and placing a member in a role both raise the security version (the trigger on `security_role`, V044, and the one on `member_access`, V028), and the description a member gets carries the role and its chain, so the next question on every instance gives the new answer. [ADR-0053](0053-the-security-cache-and-its-version.md) lists `security_role` among the tables left out; V044 later added the trigger and `SecurityVersionIT` lists it, so the list in ADR-0053 is out of date and is corrected by this paragraph. Shown by `RoleSharingIT` and, on three instances, by `RecordAccessCacheIT`.

### 8. Taking a share back, the view, the audit

- `DELETE .../shares/{granteeId}` is keyed by the grantee identifier alone, as before. A user, a group and a role are rows of three different tables, so the identifier names one share of the record; the unique index is on the same pair. A role share is removed by the role's identifier (proved).
- `RecordShareView` gains `granteeLabel`: the name of the group or role, absent for a person (a person is named through the labels of the owner field, as a link is) and absent for a group or role that no longer exists. An identifier is never what a person reads.
- Audit: `data.record.shared` / `unshared` carry the **kind** and level only, as before; no identifier of a person, no label of a role or group, in any log, event or audit record of a share (`RecordAccessFlowsLogsAreCleanIT`). The audit of a removal carries `shares_ended`.
- A share is allowed on a record locked by a pending approval (it changes who may see it, not the record), for roles as for people ([ADR-0148](0148-record-locking-while-an-approval-is-pending.md); `RecordLockIT`).

## How the first use sets it up (the product owner's answers, 2026-10-22)

The first user of the capability publishes an approved document to the people who work at three levels, each person at exactly one. The three levels are a **tree of roles** (the top level, a middle level under it, and below that one role for each department); every person holds the one role that is theirs. A document published to one department's role (`ROLE`) is read by that department only; published to a level and the levels below (`ROLE_AND_SUBORDINATES`) it is read by that level and everything under it. The records the people of a department own are read by the levels above them through the existing role hierarchy of the object (reading only, never changing) when the object is Private with the hierarchy switch on. Roles and the object's sharing setting are already configurable in setup (`/setup/roles`, the sharing tab of the object); nothing here adds a screen for it. Groups remain for lists that cut across levels.

## Alternatives considered

- **Untyped identifiers (the smallest change).** A role identifier in the set of grantees would match a share of any kind with that identifier. Correct today because identifiers do not collide, but it makes the safety of the match depend on a property of the generator of identifiers; ADR-0178 asked for the typed match and the cost was moderate (nine production files, six test files).
- **Opening the setup list of roles to anyone who may share.** Rejected: it hands a setup ability to people who have none, and it lists members' counts.
- **Leaving the shares of a removed role.** Harmless but untidy; ending them is one hook.
- **A new index with the kind in it.** Measured, no gain.

## Consequences

- FA4 can publish and unpublish by creating and deleting shares on the header record in one transaction with the version status change; nothing else is needed from the platform.
- A new kind of grantee ("everyone with this profile") is one more `Grantee.Kind`, one more meaning in the resolver, one more branch in the statement and one more value of the check.
- The share table keeps `grantee_type` as text with a check; the kinds are not an enumerated type, so a new kind is a check-constraint migration.
