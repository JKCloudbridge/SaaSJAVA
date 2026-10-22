# ADR-0189: Who sees a template and who may do what: the fixed private default, abilities and field rights in shipped policies, the approver's edit and the open-task read

- **Status:** Accepted (the product owner delegated every technical choice on 2026-10-22 and answered the plan's questions 3, 4 and 6 with "your recommendation")
- **Date:** 2026-10-22
- **Sprint:** FA4 (Functional track)
- **Related:** [ADR-0188](0188-the-template-lifecycle-the-cluster-module-publishing-and-the-master-list.md), [ADR-0115](0115-the-record-access-model-and-how-it-reaches-a-statement.md) and [ADR-0116](0116-record-access-defaults-roles-sharing-rules-and-explicit-shares.md) (record access), [ADR-0046](0046-groups-and-the-licence-rule-for-policies.md) to [ADR-0052](0052-groups-permissions-on-data-and-platform-functions-what-sprint-8-leaves-out.md) (permissions on data), [ADR-0146](0146-who-may-decide-and-the-task.md) and [ADR-0148](0148-record-locking-while-an-approval-is-pending.md) (a task grants no access; the lock), [ADR-0174](0174-template-versions-and-approval-together.md) section 4, [ADR-0180](0180-names-the-module-the-objects-and-the-words.md) (the three abilities it named are replaced by section 2), [ADR-0181](0181-the-solution-framework.md) (shipped policies)
- **Evidence:** `TemplateLifecycleIT`, `TemplateAccessIT`, `TemplateListIT`, `ReviewEditIT`, `SharingDefaultIT`, `ShippedPoliciesIT`, `SolutionLoaderTest`, `SolutionValidatorTest`, `StandardMetadataLoaderTest`, `SheetVersionGuardIT`, `SheetWorkspace.test.tsx`

## Context

An object nobody has set up is Public Read/Write (ADR-0116): every member who may read templates would read every template, which breaks the rule "a template is visible to a person only if it was published to them". The shipped policies of FA1 held the read right on the first object and nothing else, so nobody could write, approve, publish or retire. The product owner's rule is that while a template is in approval only the people who approve it can change it, audited. This record settles who sees what and who may do what, in the three separate mechanisms (licence, access policy, plan feature).

## Decision

### 1. A shipped object can have a default the platform fixes (`sharingDefault: PRIVATE`)

An object file may say `sharingDefault: PRIVATE`. Then, for every organization that has the object:

- **a record is reached only by** its owner, a member whose policy gives them *view all* (or *modify all*) on the object, a member it is shared with (a person, a group, a role) and a sharing rule an administrator made; **the role hierarchy adds nothing** (the "managers read what their people own" switch is off);
- **no administrator can change it**: the sharing setup answers a change with `CONFLICT` in fixed words and shows the object as fixed (`ObjectSharingView.fixed`); and a settings row that got in some other way is **not even read** (`DefaultRecordSecurity` ignores it for such an object), so nothing a row says changes an answer.

This differs from the plan's first idea, a row written lazily per organization and guarded by a trigger like the shipped policies. The attribute needs no write per organization, no start-up sweep and no fingerprint, cannot go stale or be missing, and is read where the answer is computed (the metadata object the security module already reads through `ObjectCatalog`). Its cost is that the database does not guard it; the resolver and the API do, and a test inserts a row as the owner of the database and shows it changes nothing (`SharingDefaultIT`). Sharing *rules* (an explicit, named, audited act of an administrator) still add access to a fixed object, as they do to any private one.

### 2. Two generic abilities, not three of the cluster's

`Ability` is a closed catalogue in generic code that the permission calculation, the profile editor and its property tests rely on; a bundle cannot add to it without making it an open set. ADR-0180 named `templates.view`, `templates.publish` and `templates.retire`. Built instead: **`sheets.publish`** (make an approved version the one in use, choose who it is published to, take it back) and **`sheets.retire`** (retire what has a sheet), words of the generic module `sheets`. `templates.view` is not needed: seeing the list is the read right on the object. Approving stays the approval abilities and tasks; it is not publishing.

### 3. Shipped policies carry abilities and rights on fields

`solution.yml` policies may now list `abilities` (keys of the catalogue, each checked at load) and `fields` (for each object, the fields to `read` and to `edit`, each checked against the object: it exists and is not retired (a system field such as the owner or the time of creation may be named), and the policy gives a right on the object). The fingerprint includes both, only when there are some. The finding behind the second: a member who may not read a field has no such field (ADR-0050), so an author with the right to update could not even set a template's name until the policy said which fields. What ships:

| Policy | Rights on the object | Fields | Abilities |
|---|---|---|---|
| Author | template: create, read, update | edit name, category, sub-category, part code, plan year; read number, retired and the five system fields (owner, time and person of creation and of the last change) | none |
| Approver | template: read, update, view all, modify all | the same | `approvals.override-lock`, `sheets.publish`, `sheets.retire` |
| Reader | template: read | read the seven | none |
| Filler | template: read | read the seven | none |
| Department setup | department group: create, read, update, delete | edit its four fields | none |

No policy gives the delete right on a template (its records are kept; `modify-all` implies it and the decision API still refuses it). The policies are not bound to a licence; the plan feature `inspection` decides whether any of this exists. The organization's administrator gives them to people by role or group (the manual steps say how).

### 4. The approver's edit of a version in review

Decision 13 of the product owner, built as ADR-0174 section 4 said: an approver may change the grid of the version under review, audited. Where it is asked: **`SheetOwners.mayCorrectInReview`**, and nowhere else: the member may change the record (under the private default, *modify all* is what lets an approver who does not own a template do so) **and** holds `approvals.override-lock`, because a pending request locks the record for everybody and the ability is what passes the lock. The creator who submitted it has neither by that act and is refused (`403`, fixed words). The save is the ordinary `PUT .../grid`; for a version in review it opens the database's window for that one statement (`platform.review_edit`, switched off again at once), raises `review_edits` by one, then writes the history of the request (`RecordLocks.overridden`) and an audit record of the correction by size, counts and the fingerprint of the grid, never its text. A grid identical to the current one is not a correction: it is answered as it is, counted and audited as nothing (the database counts a review edit only when the grid changes; the first version of this code met that guard as a 500 and a test now holds it).

The database words, from V064: *"sheet guard: the grid of a version of sheet_version that is not a draft cannot change"* for everyone else, as the application role and as the owner of the database alike, and *"sheet guard: only an edit of the grid counts as a review edit in sheet_version"* when the count rises without the grid changing.

### 5. A person asked to approve reads that one version

An approval grants no access to the record (ADR-0146) and that stays true. But a person named in a process who holds no right on the template could not see what they are asked to approve. The shared-kernel question **`ApprovalTasks.holdsOpenTask(request, person)`** (implemented by `approval`, a yes or no for a pending request, delegates included) lets `sheets` show such a person **the version that request is reviewing, read-only, and nothing else**: the list answers with that version alone, the version opens, and the record, every other version, the lifecycle, a save and a new draft answer "not found". Once they have decided there is no open task and the version is not theirs any more. The Approver policy covers the people it is given to; this covers a user, a delegate or a member of a role named in a process who was not given it. The inbox still names the record only when the member can read it.

### 6. Authors and approvers are not narrowed apart

"May change the sheet" is "may update the record" (ADR-0186); the Approver policy holds *update* and *modify all*, so an approver can also start a draft and an author can start one on their own templates. The product owner decided on 2026-10-22 that authoring stays the ordinary update right, so `SheetOwners` is not narrowed. No hole was found: an approver cannot submit someone else's draft as theirs (the engine checks the submitter's right on the record, and an approver's right is the same one), and nobody can change a version that is not a draft except the correction above.

## Consequences

- `ObjectDefinition`, `ObjectCatalog.ObjectInfo` and `ObjectSharingView` gain the fixed default; the baseline of released definitions records it (`sharingDefault=PRIVATE`) and the attribute `platformWritten` on the template's `retired` field.
- Two abilities join the catalogue; `ShippedAccessPolicies.Declaration` and the bundle's `Policy` gain abilities and field rights.
- The Approver policy is powerful on purpose (it sees and can correct every template). Giving it is the organization administrator's act and is audited like any assignment.
- The sheet tests of FA3 that relied on a reader reading a record created by someone else were updated: under a fixed private default the record is shared first.
