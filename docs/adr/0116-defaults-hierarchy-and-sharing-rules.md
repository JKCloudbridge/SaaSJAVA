# ADR-0116: Defaults, the role hierarchy and sharing rules

- **Status:** Accepted
- **Date:** 2026-10-13
- **Sprint:** S17
- **Related:** [ADR-0115](0115-the-record-access-model-and-how-it-reaches-a-statement.md), [ADR-0047](0047-public-groups.md), [ADR-0053](0053-the-security-cache-and-its-version.md), [ADR-0117](0117-explicit-sharing-and-who-may-share.md), [ADR-0118](0118-view-all-modify-all-and-one-evaluation.md)

## Context

A member who may read an object must, from Sprint 17, reach only some of its records. Three sources widen what an owner alone can reach: the role hierarchy (a manager and the people below), public groups with sharing rules, and the organization-wide default of the object. The platform already has the role tree (Sprint 7), public groups with nesting (Sprint 8) and the permission decision API, which "accepts and ignores" a record (ADR-0050).

## Decision

1. **The organization-wide default of an object is one of three values:** `PRIVATE` (only what the sources below give), `PUBLIC_READ` (everyone who may read the object reads every record; only the owner and the sources below change one) and `PUBLIC_READ_WRITE` (everyone who may read the object reads and changes every record). **An object with no setting is `PUBLIC_READ_WRITE`**, which is what every
   object was until Sprint 17: no organization changes behaviour by upgrading, and nothing is stored until an administrator chooses. The setting is a tenant-scoped row, `object_sharing_setting` (V044), changed with the version read, audited, and it raises the security version.
2. **Delete is not shared.** A record is deleted by its owner, by a member with modify-all on the object, and by anyone when the default is `PUBLIC_READ_WRITE`. The hierarchy, a rule and an explicit share never give delete. Reason: a wrong delete cannot be seen or undone by the person who lost the record (the recycle bin helps, but only for 30 days), and no common sharing need is served by it.
3. **The role hierarchy gives read, and only read, upwards** and is a switch per object (`grantViaHierarchy`, default on). A member reads the records owned by people in the roles below their own, at any depth. The hierarchy is made of memberships (`member_access.user_id`, ADR-0115 point 6), so the owners behind it come from one table that `security` owns. Why read only: a manager who may change every record of a worker has the
   same effect as modify-all with none of the audit trail a deliberate grant leaves; an organization that wants it creates a sharing rule with the level `READ_WRITE` from the worker's role to the manager's group, which is visible, named and audited.
4. **Sharing rules** (`sharing_rule`, V044) give a group (or a role's members) access to records, at the level `READ` or `READ_WRITE`, never delete. Three kinds: `OWNER` (records owned by the members of a role, `ROLE`, or of that role and the roles below it, `ROLE_AND_SUBORDINATES`), `GROUP` (records owned by the members of a public group, nesting resolved) and `CRITERIA` (records whose fields meet a condition). The condition of a criteria rule is the **same closed filter model as a
   query** (ADR-0100), checked when the rule is saved by the query analyzer (the fields must exist and the operators fit the types) through the SPI `SharingCriterionChecker` which `data` implements; a rule is therefore never able to say anything a query cannot, and no text of an organization is executed. A field that a criteria rule uses cannot be removed while the rule exists: the rule is an edge of the dependency graph (`SharingDependencies`) like any other definition.
5. **Targets and sources are checked when the rule is saved and at every question.** A target group that is deleted, or a role that is deleted, makes the rule give nothing; it is never an error for the person asking. A rule can be switched off (`active`) without being deleted.
6. **Union only, as in Sprint 7 and 8.** The defaults, the hierarchy, every rule and every share are added; nothing subtracts. The only thing that ever gives less than the union is the permission on the object itself: record access never lets a member do what the profile and the access policies do not (a member who may not update the object cannot update any record, whatever a rule says). `Decisions` combines the two:
   an action on a record is allowed when the object permission allows it **and** the record is admitted.
7. **Limits:** 50 rules per object (`platform.security.records.max-rules-per-object`), checked in the service; 80 characters for a name. The owners behind one member's description are bounded as in ADR-0115 point 3.

## Consequences

- With no setting nothing changes, and the cost of the first sharing setting is one cached description per member (ADR-0115 point 5).
- The default answers one question for the whole object, the rules and the hierarchy answer per member, and the explicit shares answer per record (ADR-0117), so each can be reasoned about, tested and explained on its own.
- Because delete and the hierarchy are narrower than in some other products, an administrator who needs "my manager may change my records" must say so with a rule; this is deliberate.
