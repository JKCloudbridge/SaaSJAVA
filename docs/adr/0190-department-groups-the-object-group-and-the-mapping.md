# ADR-0190: Department groups: the object Group, the mapping of Location and Department to a group, and what a publish does with it

- **Status:** Accepted (the product owner decided on 2026-10-22 that the people of a department are a group an administrator keeps, and answered the plan's question 2 with "your recommendation": a mapping object, several departments in one publish, a new directory for groups)
- **Date:** 2026-10-22
- **Sprint:** FA4 (Functional track)
- **Related:** [ADR-0188](0188-the-template-lifecycle-the-cluster-module-publishing-and-the-master-list.md), [ADR-0099](0099-links-to-objects-another-module-owns.md) (the reference directory), [ADR-0129](0129-record-screens-the-members-description-names-and-validation.md) (the names behind links), [ADR-0177](0177-dependent-picklists.md) and [ADR-0182](0182-the-organizations-own-list-of-a-platform-picklist.md) (the dependent lists), [ADR-0185](0185-shares-to-roles-the-typed-grantee-and-the-choice-of-roles-and-groups.md) (groups as grantees)
- **Evidence:** `GroupDirectoryIT`, `TemplatePublishingIT`, `DepartmentAudiencesTest`, `RecordSharesIT`, `StandardMetadataLoaderTest`, `PublishDialog.test.tsx`

## Context

A template is published to "the people of a department", and a department belongs to a location. Decided: those people are a public group an administrator keeps in the existing group setup, not a rule on each person's profile. The platform therefore needs a small mapping from a pair of Location and Department to that group, a way for a field to point at a group, and a publish that resolves the pair without the approver needing any right on the mapping.

## Decision

### 1. The mapping is a shipped object, `DepartmentGroup`

One record for each pair: `location` and `department` (two picklists, the second depending on the first, both with the organization's own lists and short codes: ADR-0177, ADR-0182), a `groupId` (a link to a group) and a `name`. The platform ships a starting list of placeholders that each organization replaces. It is an object of the solution (it needs the plan feature; it appears in the application as "Department groups" for whoever holds the **Department setup** policy), so the administrator keeps it with the ordinary record screens: list, form with the narrowing department list, import. Nothing needs a screen of its own.

The database cannot make a *pair* of picklists unique (the uniqueness of FA2A covers a field and the two parents of a junction). The publish therefore refuses an ambiguous pair in plain words ("More than one group is set up for this department. Ask your administrator to keep one.") instead of choosing.

### 2. A field can point at a group: the object `Group` and its directory

No directory existed for the objects the security module owns (the contract mentioned a profile and a role; only the user had one). Built: the standard object **`Group`** (kept by `security`, never stored by the record store), and **`GroupDirectory`**, implemented by `security`, answering the two shared-kernel contracts the record store already collects: *is this identifier a live public group of this organization* (`ReferenceDirectory`) and *what is it called, which are the groups for a picker* (`ReferenceLabels`). A group that does not exist, one of another organization and one that was removed all answer alike. Names of groups are the organization's setup and no secret to its members; the people in a group are never asked. No module edge is added; the smallest generic piece, in the same place as the directory of users.

### 3. A publish resolves the pair as the platform

`DepartmentAudiences.resolve` reads the mapping through `BackgroundRecords` (no member gate): the approver who publishes needs no right on `DepartmentGroup`. Every pair is checked before anything is shared, and the first pair that cannot be published to refuses the **whole** request with `VALIDATION_ERROR` and the place `audiences[i]`:

| Refusal | Words |
|---|---|
| no record for the pair | "No group is set up for this department yet." |
| several records for the pair | "More than one group is set up for this department. Ask your administrator to keep one." |
| the group was removed | "The group set up for this department no longer exists." |
| the group has no members (nested groups counted) | "The group set up for this department has no members yet." |
| the same pair twice in one request | "This department is listed twice." |

The words name neither the location, the department nor the group. A publish to nobody would look as if it worked, which is why an empty group is a refusal.

### 4. The dialog's choices

`GET /api/v1/templates/audience-options` (ability `sheets.publish`) gives every location of the organization's list with the departments that go with it (the dependency, so the department narrows to the location) and whether a group is set up for each. The screen offers every department, marking those with no group, because the server is the one that says no, in its words. The labels come from the organization's own lists.

### 5. Several departments in one publish

Yes, a list of up to 20, all or nothing. Two pairs that mean the same group make one publication (the first pair is remembered). A group published to and not asked for again is taken back in the same transaction.

## Consequences

- `Group` joins the standard objects (the baseline records it); `DepartmentGroup` is the second object of the solution. `SolutionBoundaryTest` keeps the words in the cluster's places.
- Removing a group does not clear the link of a mapping record that points at it (the record store holds no link to a platform-owned object); the publish says the group no longer exists. An administrator fixes the record.
- Adding a department or a location is the organization's act on its own lists (the field-values ability or the object manager), and the pair needs a mapping record before anything can be published to it.
