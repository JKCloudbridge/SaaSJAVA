# ADR-0039: Abilities, profiles, access policies and individual grants

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S7
- **Related:** [ADR-0026](0026-membership-lifecycle-and-the-administrator-marker.md) (the marker this replaces),
  [ADR-0032](0032-licences-pools-assignments-and-their-rules.md), [ADR-0040](0040-effective-permissions-and-the-read-contract.md),
  [ADR-0043](0043-replacing-the-administrator-marker.md), [ADR-0044](0044-the-last-member-who-can-manage-access.md); delivery plan S7,
  architecture note 2 (sections 13 to 18 and 32)

## Context

Until Sprint 6 one yes/no marker on a membership decided whether a person could administer an organization. Sprint 7 replaces it
by the model of the written design: what a person may do comes from a **profile** (the base), **access policies** (additions,
called "permission sets" in the written notes) and **individual grants**, while the **role** hierarchy only decides, later, which
records a person may see. Objects, fields and records do not exist yet (Milestones 3 and 4), so the first things a person can be
allowed to do are the administrative actions that existed since Sprint 5.

## Decision

1. **An ability is a short key known to the code.** `Ability` (module `security`) lists them: `members.view`, `members.invite`,
   `members.deactivate`, `licences.manage`, `sessions.manage`, `support-access.manage` and `access.manage`. They map one to one to
   what `Administration` guarded since Sprint 5; `access.manage` covers profiles, access policies, roles, assignments and
   individual grants. The catalogue is in the code (not a table) because every ability needs exactly one place that checks it;
   the database stores the key as text and checks only its shape. A key a later release no longer knows is **ignored** when
   permissions are computed, never an error, so dropping an ability cannot break an organization. Sprint 8 adds object and field
   permissions next to these without changing how abilities are stored or combined.
2. **A profile** belongs to one organization, has a name (unique per organization, case-insensitive), a description, a list of
   abilities and **one licence type** (decided by the product owner: a profile belongs to a specific user-licence type; the first two
   types are `user` and `admin`). A member has **exactly one profile**. Organizations create their own profiles
   (`POST /api/v1/profiles`), so a profile that may invite but not deactivate is just a profile with `members.invite`. Two profiles
   exist in every organization from the first day (ADR-0045): the system **Organization administrator** (every ability, always,
   licence type `admin`, cannot be changed or removed) and the system **Member** (licence type `user`, no abilities until the
   organization adds some, the default for new members, can be changed but not removed). One profile is the **default** for new
   members; an administrator may choose another (never the administrator profile). A profile that members hold cannot be removed
   and keeps its licence type.
3. **An access policy** is a named list of abilities added to the members it is assigned to. A member may hold any number. It may be
   **licence-bound** (`required_licence_type`, empty by default): assigning it uses one licence of that type from the pool, refused
   with `409` when none is free (the last free licence has one winner under concurrency: the pool lock of ADR-0032), and unassigning
   it or ending the membership gives the licence back. A policy that members hold cannot be removed and keeps its licence type.
4. **An individual grant** gives one member one ability directly, with an optional note (at most 200 characters) saying why. The note
   is stored on the grant and shown only to people who manage access; it is never written to a log, a mail or an audit record
   (the audit record only says that a note exists), and the request prints as `[redacted]`.
5. **Licences follow profiles.** A profile uses one licence of its type: giving a member a profile takes a licence of that type
   from the pool (and returns the one they held for the old profile), refused with `409` when none is free. When a membership
   starts or returns (acceptance, founding, reactivation) the licence is tried but **never refuses the person**: they join with
   the profile recorded and without its abilities until a licence is free (ADR-0045). A licence still only counts and limits
   assignments; the three mechanisms (licence, permission, feature entitlement) stay separate and a test proves each changes alone.
6. **Who manages access.** Members who hold `access.manage` (administrators do through their profile; a custom profile may carry it).
   Nobody else can change a profile, a policy, a role, an assignment or a grant. A platform role gives none of this: a platform
   person who opened an organization's context has no membership and therefore no abilities (ADR-0030).
7. **Nobody gives what they do not have.** A member who may invite but not manage access can only invite someone with a profile whose
   abilities they hold themselves (the default profile is always allowed): holding `members.invite` alone must not be a way to
   create administrators.
8. **Every change is audited** in the caller's transaction with the actor, the member, the identifiers and the ability keys
   (`access.profile.*`, `access.policy.*`, `access.role.*`, `access.member.*`); a refusal for lack of the ability is audited
   after its transaction ended (`access.action.refused`, `membership.action.refused`).

## Consequences

- One small class asks "may this member do this?" (`Administration` in identity, `AccessGate` in security); the answer is always
  the effective abilities of the caller read from the database, never a marker.
- Because objects do not exist, the abilities are administrative only; the shape (a key in a list, in three containers) is what
  Sprint 8 extends.
- The written text called access policies "permission sets"; the product words are *access policy* everywhere (user decision).

## Alternatives considered

- *Abilities in a platform table.* Rejected: a table could be edited into a state no code checks; the key list in the code and the
  tests that read it keep checks and catalogue together.
- *A role that carries abilities.* Rejected by the written design: roles are a visibility hierarchy (ADR-0042).
