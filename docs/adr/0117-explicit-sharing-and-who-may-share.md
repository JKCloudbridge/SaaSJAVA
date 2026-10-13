# ADR-0117: Explicit sharing of a record and who may share it

- **Status:** Accepted
- **Date:** 2026-10-13
- **Sprint:** S17
- **Related:** [ADR-0115](0115-the-record-access-model-and-how-it-reaches-a-statement.md), [ADR-0116](0116-defaults-hierarchy-and-sharing-rules.md), [ADR-0118](0118-view-all-modify-all-and-one-evaluation.md), [ADR-0119](0119-record-predicates-in-the-plan-and-the-owner-index.md)

## Context

A person needs to show one record to one colleague or one group without an administrator and without changing a rule that covers a thousand records. The record also needs an owner who can change: people move, and the owner of a record is the one fact the whole access model hangs on.

## Decision

1. **A share is one row of `record_share`** (V045): the record, a grantee (a user or a public group) and a level, `READ` or `READ_WRITE`. One share per record and grantee: sharing again changes the level (a partial unique index, never a second row). A record has at most 100 shares (`platform.data.sharing.max-shares-per-record`): more is what a group or a rule is for.
2. **The table belongs to `data`, not to `security`.** The share names a record, and a record is `data`'s. `security` only says who the member is *for sharing*: their user identifier and their groups (nesting resolved by `GroupMembers`). The statement reads the live share rows (ADR-0115 point 5): **a share needs no cache invalidation and sharing a record does not raise the security version.** The cascade from `object_record` removes the shares when a record is removed for good; a record in the recycle bin keeps them, so that a restore gives back what it had.
3. **Who may share, and who may give a record away:** whoever may *change* the record under the same evaluation as everything else (`Decisions.can(..., EDIT, RecordRef)`): the owner, a member that the default or a rule lets change it, a member the record is shared with at `READ_WRITE`, a member with modify-all. A member who can only read the record is told "forbidden" (they know the record exists); a member who cannot reach it is told it does not exist, with the same words as for an identifier that was never used. Listing the shares needs the same right (the list names people).
4. **What is refused, in plain words:** sharing with the owner (it would give nothing), with a user who is not a member of the organization, with a group that does not exist, a level that is not one of the two, a share beyond the limit. A share never gives delete (ADR-0116 point 2).
5. **Changing the owner** is its own action with the version read (`POST .../owner`), not a field of an update: the new owner must be a member; a lost race is a conflict; the record's version rises (the owner is part of the record). It needs the right to change the record. The previous owner reaches the record only as the rules say afterwards; nothing is kept for them. The owner is a typed column of the row, so the index on the owner (ADR-0119) is current in the same statement.
6. **Audit and events** carry identifiers only: `data.record.shared` and `data.record.unshared` (grantee type, grantee identifier, level, the record, the object) and `data.record.owner_changed` (the previous and the new owner). No field value is in any of them.
7. **A share and an owner change pass the gate of the object** like any other write to a record (an object a platform module owns has no shares; a read-only member is refused before anything is looked at).

## Consequences

- Sharing is visible at once on every instance, with no invalidation to get wrong.
- The cost is that each question about a Private object reads the share rows (an index on `(tenant, record, grantee)`); the measured cost is in ADR-0119.
- A record that is shared with a group follows the group: members added later see it at their next question; members removed lose it.
