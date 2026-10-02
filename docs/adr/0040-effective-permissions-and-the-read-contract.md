# ADR-0040: Effective permissions: the algorithm and the one read contract

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S7
- **Related:** [ADR-0039](0039-abilities-profiles-access-policies-and-individual-grants.md),
  [ADR-0042](0042-the-role-hierarchy.md); delivery plan S7 (exit: effective permissions computed deterministically from a documented
  algorithm with property-based tests), S8 and S9

## Context

What a member may do must be computed the same way everywhere, from facts that can be changed independently, and the result must
not depend on the order in which things were assigned. Sprint 8 will add object and field permissions to the same containers and
Sprint 9 decides whether the answer is cached.

## Decision: the algorithm

`EffectivePermissions.compute(profile, policies, grants)` is a pure function (it reads and writes nothing):

1. **Profile.** The member's one profile contributes its abilities (every ability for the administrator profile) **only while the
   member holds the licence of the profile's type**; without that licence it contributes nothing. This is the only place a licence
   touches abilities, and it does so by deciding whether the profile counts, not by being an ability.
2. **Access policies.** Every policy assigned to the member contributes all its abilities (a licence-bound policy cannot be
   assigned without its licence, so it is not asked again).
3. **Individual grants.** Every directly granted ability is contributed.
4. **Union.** The result is the union of the three. **There is no deny rule in this sprint.** Nothing takes an ability away except
   removing what gave it. Reasons: it is how the written design is stated (profile + permission sets + individual grants); a union
   is the same in every order and grouping, which is what the property tests rely on; a deny rule needs a precedence decision that
   is better made together with object and field permissions (Sprint 8), where a deny is meaningful. The written design's
   "individual access: grant / revoke" belongs to record sharing (Sprint 17), not here.
5. **The role takes no part.** No role, role hierarchy or loop is an input, so none can change a result (the signature has three
   inputs and a test pins it).
6. **Unknown keys** stored for a profile, policy or grant (an ability a later release dropped) are ignored.

### Properties (checked on random input with property-based tests)

The order of policies does not matter; adding a grant, a policy or an ability to a policy never removes an ability; removing a
policy and adding it again gives the same result; nothing gives nothing; a profile without its licence contributes nothing; a
licensed administrator profile holds every ability; the result equals an independently written definition of the union.
The tests were each checked to fail when the calculator is changed on purpose (see `Sprint 7.md`).

## Decision: the read contract

`Permissions.effective(membershipId)` and `Permissions.has(membershipId, ability)` (module `security`, root package) are the one way
other modules ask. They answer for the organization of the thread's tenant context (never one named by a caller), read the
current rows every time (**no cache; staleness is zero**), run in the caller's transaction, and give the same empty answer for a
member who does not exist as for one with nothing, so asking reveals nothing. Sprint 9 decides invalidation if a cache is added.
Every action asks inside the transaction that does the work, so the answer and the action cannot drift apart. The backend is the
only authority: `GET /api/v1/auth/me` returns the caller's abilities for presentation, and the screens show only what they answer,
but every request is decided again by the server.
