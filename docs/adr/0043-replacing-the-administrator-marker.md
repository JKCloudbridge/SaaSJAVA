# ADR-0043: Replacing the administrator marker; creating a member; leaving

- **Status:** Accepted
- **Date:** 2026-10-02
- **Sprint:** S7
- **Related:** [ADR-0026](0026-membership-lifecycle-and-the-administrator-marker.md) (superseded in part),
  [ADR-0028](0028-invitations.md), [ADR-0039](0039-abilities-profiles-access-policies-and-individual-grants.md),
  [ADR-0044](0044-the-last-member-who-can-manage-access.md); delivery plan S5 (the marker), S7

## Context

Sprint 5 gave a membership a changeable `administrator` marker as a stop-gap, asked in one place (`Administration`), with the
rule "the last active administrator stays" in the database. Sprint 7 replaces the marker by abilities.

## Decision

1. **`Administration` asks for an ability, not a marker.** `Administration.run(action, ability, work)` (and
   `OrganizationAdministration.asAdministrator` for other modules) checks, inside the transaction of the work, that the caller is an
   active member and that `Permissions.has(membership, ability)`. The ability of each action:

   | Action | Ability |
   |--------|---------|
   | list members | `members.view` |
   | invite, list, resend, withdraw invitations | `members.invite` |
   | deactivate, reactivate | `members.deactivate` |
   | licence pools, give or take a licence | `licences.manage` |
   | sign everyone else out | `sessions.manage` |
   | support access: list, approve, deny, revoke | `support-access.manage` |
   | profile, role, access policy, assignment, grant | `access.manage` |

   A refusal is audited after the transaction (`membership.action.refused`, reason `missing_ability`). A caller without a membership
   (a platform person) gets the same `403` as a member without the ability.
2. **The marker is expand then contract.** From this sprint nothing reads or writes `membership.administrator` or
   `invitation.administrator` (the migration V022 replaces the membership guard without the marker rule; V023 gives every existing
   administrator the administrator profile). The columns stay, unread, for one sprint so a rollback of the code is harmless;
   **a contract migration drops them in Sprint 8** (nothing is deployed to production yet, so nothing is at risk).
   `founding_administrator` stays: it is a historical fact and grants nothing. `PUT /members/{id}/administrator` is removed:
   naming an administrator is now "give the member the administrator profile".
3. **An invitation creates a member.** The administrator fills in the e-mail address, the person's name (optional), the **profile**,
   the **role** (optional) and whether it is **active** (send the link now; off saves it unsent for later). The person gets the
   same one-time link of Sprint 5 and only chooses a password (and a name when none was entered). The invited person never chooses
   a profile. The invitation record holds `profile_id`, `role_id` and `display_name`; **a membership still exists only after
   acceptance** (ADR-0026) and the request does identical work for every address (ADR-0028). Platform-provisioned first
   administrators always get the administrator profile and an administrator licence, whatever the invitation says.
   The profile and role are checked inside the transaction (a profile of another organization is a validation error; a
   profile whose abilities the inviter lacks is `403`, ADR-0039 point 7). If the profile or role was removed between the invitation
   and the acceptance, the person gets the default profile and no role: acceptance never fails.
   There is no separate username: people sign in with their address; "username" is the display name.
4. **A member may leave by themselves** (deferred from Sprint 5): `POST /api/v1/organization/leave`, any active member, no ability
   needed. The membership becomes deactivated, licences go back, sessions of that organization end, an administrator can let the
   person back in. The last member who can manage access cannot leave (`409`, ADR-0044).
5. **API changes** (the client is regenerated): `MemberView` loses `administrator` and gains profile, role, access policies and
   whether the member holds the licence their profile needs; `OrganizationSummary` loses `administrator` (it cannot be answered
   across organizations under row level security and nothing used it); `InviteRequest` gains name, profile, role and `active` and
   loses `administrator`; `InvitationView` gains name, profile and role; `InvitationPreview` gains the entered name;
   `AcceptInvitationRequest.displayName` is optional; `CurrentUser` gains `abilities` (for presentation only);
   `PUT /members/{id}/licence` takes no body (it gives the licence the member's profile needs).
