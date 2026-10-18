# ADR-0146: Who is asked: approver resolution

- **Status:** Accepted
- **Date:** 2026-10-18
- **Sprint:** S24
- **Related:** [ADR-0145](0145-the-approval-model-processes-versions-instances-and-states.md), [ADR-0043](0043-replacing-the-administrator-marker.md),
  [ADR-0047](0047-public-groups-and-nesting.md), [ADR-0099](0099-the-reference-directory-links-to-objects-a-platform-module-owns.md),
  [ADR-0125](0125-record-level-security-on-writes-links-and-uniqueness.md)

## Context

The architecture note names specific user, role, manager, group and a dynamic user from a field (note 6, section 17), and a
permission-based approver. This platform has profiles (which carry a licence), a role hierarchy that decides **record visibility only**
(Sprint 7: it is never read to decide an ability) and public groups with nesting.

## Decision

1. **Closed kinds, resolved when the step becomes active**, each with one setting:

   | Kind | Setting | Resolves to |
   |------|---------|-------------|
   | `USER` | a user identifier | that person |
   | `PROFILE` | a profile identifier | the active members who hold the profile |
   | `ROLE` | a role identifier | the active members placed in the role |
   | `GROUP` | a public group identifier | the active members of the group, nesting resolved by `GroupMembers` |
   | `OWNER` | none | the owner of the record |
   | `RECORD_USER` | a user field of the object | the person the field names on the record |
   | `OWNER_ROLE_MANAGER` | none | the active members placed in the parent role of the record owner's role |

   `OWNER_ROLE_MANAGER` is the "manager" of the architecture note. It reads the role hierarchy to find **people to ask**, which is not an
   ability and does not decide what anybody may do: the person it names must be able to read the record for the task to be usable
   (decision 6) and is asked, not authorised. The Sprint 7 rule ("do not read the hierarchy to decide an ability") is therefore
   untouched; this ADR is where that is written down. The user-field kind covers an organization that keeps its own manager field.
2. **A purpose-built contract in the `security` root** answers the four questions the kinds need and nothing else: `ApproverDirectory`
   (`holdersOfProfile`, `holdersOfRole`, `parentRoleHolders`, `eligible`). It returns **membership-level facts for active members only**
   and says for each whether the member holds the licence their profile needs. It does not load every member
   (`MemberAccess.summaries()` does and has no active flag). `GroupMembers` answers the group kind.
3. **The resolved list is a snapshot.** It is stored with the step instance as assignments when the step becomes active, in the same
   transaction. A later change of a group, a role, a profile or a field does not change a step already active; a person added later is
   added by `reassign` (ADR-0147). Written down so nobody expects otherwise; a test holds it.
4. **Who is dropped**, each with a stable reason in the history (`assignee_dropped`, with `inactive`, `unlicensed` or
   `self_approval_excluded`): a person who is not an **active member**; a member who does not hold the **licence** their profile needs;
   the **submitter**, unless the process allows self-approval. The submitter of a request that the platform started (ADR-0150) is nobody, so
   nobody is excluded for it. A person named twice by several rules is asked once.
5. **A step that resolves to nobody.** The process's **fallback approver** (a user or a public group, resolved and filtered the same way)
   is used when there is one. Otherwise: for the first step the submit is refused with `no_approver`; for a later step the instance stays
   `PENDING` with `attention = no_approver` (ADR-0145 decision 6).
6. **An approver gets no new access.** The task is shown to the assignee, but the record is read, as always, with the assignee's own
   permissions and record-level security. If they cannot read it the task says "you cannot open this record" and an administrator must fix
   access. Sharing, roles and groups are never changed by an approval; no read of a record is granted to an assignee by the approval.
   A forged request for the record of a task is answered as any other record the caller cannot read: not found.
7. **A user field of the record** is read from the stored record by the platform, as the owner is; the value is a user identifier or
   nothing. A field that is not a link to a user is a dependency violation when the process is saved (it names the process). A pinned
   definition whose field has since gone resolves to nothing and raises `approver_unresolvable`.
8. **Permission-based approver** (anybody who holds an ability) is not built (ADR-0153): it would hand a decision to "everyone who
   happens to hold an ability", which is an access rule inside an approval; the group kind is the explicit way.

## Consequences

- One new contract in `security` and no new module edge for the kinds (approval already depends on `security`).
- The resolution is a pure function of facts (`ApproverResolution`), tested without a database: every kind, an empty result, dedupe,
  self-approval exclusion, delegation added.
