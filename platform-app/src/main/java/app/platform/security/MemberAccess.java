package app.platform.security;

import app.platform.sharedkernel.ActorId;
import app.platformapi.AccessPolicyRef;
import app.platformapi.MemberAccessView;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the identity module does with a member's profile, role, access policies and individual grants (ADR-0039,
 * ADR-0040). Every method works for the organization the thread's tenant context names and in the caller's transaction
 * (its own when there is none). None of them asks who may do it: identity asks {@link Permissions} for the ability
 * {@link Ability#ACCESS_MANAGE} (or the one the action needs) before it calls, so the rule "may this member?" lives in
 * one place. Every change is audited with the actor, the member and what changed.
 *
 * <p>The member must be an active member of this organization (the identity module checks it and the database refuses
 * anything else), and every profile, policy and role named is looked up in this organization only, so an identifier of
 * another organization is simply not found.
 */
public interface MemberAccess {

    /**
     * What a new or returning member got.
     *
     * @param profileId the member's profile
     * @param profileName the profile's name
     * @param licenceType the licence type key the profile needs
     * @param licensed whether the member now holds that licence; when not they join without the profile's abilities
     *        until a licence is free, and joining is never refused for it
     */
    record Joined(UUID profileId, String profileName, String licenceType, boolean licensed) {
    }

    /**
     * What ending a membership gave back.
     *
     * @param licencesReleased how many licences went back to the pool (profile and policies)
     * @param policiesRemoved how many licence-bound access policies were unassigned with them
     */
    record Left(int licencesReleased, int policiesRemoved) {
    }

    /**
     * A member's access in short, for lists.
     *
     * @param profileId the profile, or null
     * @param profileName the profile's name, or null
     * @param licensed whether the member holds the licence the profile needs
     * @param roleId the role, or null
     * @param roleName the role's name, or null
     * @param policies the member's access policies
     */
    record Summary(UUID profileId, String profileName, boolean licensed, UUID roleId, String roleName,
            List<AccessPolicyRef> policies) {

        public Summary {
            policies = policies == null ? List.of() : List.copyOf(policies);
        }
    }

    /** Makes sure the organization has its two system profiles (idempotent); called when an organization is set up. */
    void ensureSystemProfiles(ActorId actor);

    /**
     * Gives a membership that was just created its profile and role, and tries the licence of the profile's type. Never
     * fails for lack of a licence: the person joins unlicensed instead (ADR-0039).
     *
     * @param profileId the profile chosen by an administrator, or null for the organization's default profile
     * @param roleId the role chosen by an administrator, or null for none
     * @param administrator whether the member gets the system administrator profile (a platform-provisioned first
     *        administrator, the founder, or an invitation made before profiles existed); it wins over {@code profileId}
     * @param founder whether the member is the first administrator, who always gets an administrator licence: the pool
     *        grows by one if it has none free
     */
    Joined join(UUID membershipId, UUID profileId, UUID roleId, boolean administrator, boolean founder, ActorId actor);

    /**
     * A deactivated member comes back with the organization's default profile and a licence if one is free, and nothing
     * else: what they held before ended with their membership (ADR-0045). Never fails for lack of a licence.
     */
    Joined returned(UUID membershipId, ActorId actor);

    /**
     * A membership ends (deactivated, or the member leaves): every licence goes back, and the access policies,
     * individual grants and the role end with it. The profile stays recorded until the member returns.
     */
    Left left(UUID membershipId, ActorId actor);

    /** Everything about one member's access. */
    MemberAccessView view(UUID membershipId);

    /** The access of every member of the organization with a profile, by membership. */
    Map<UUID, Summary> summaries();

    /**
     * Gives the member a profile. The member then holds a licence of the profile's type (taken from the pool; the one
     * they held for the old profile goes back), or the change is refused.
     *
     * @throws app.platformapi.ApiException {@code NOT_FOUND} for a profile of another organization or none,
     *         {@code CONFLICT} when no licence is free or the change would leave nobody who can manage access
     */
    void setProfile(UUID membershipId, UUID profileId, ActorId actor);

    /**
     * Gives the member the licence their profile needs, when they have none (for example after joining unlicensed).
     *
     * @throws app.platformapi.ApiException {@code CONFLICT} when none is free, {@code NOT_FOUND} when the member has no
     *         profile
     */
    void giveLicence(UUID membershipId, ActorId actor);

    /** Takes the licence for the member's profile back; the profile then gives no abilities. */
    boolean takeLicenceBack(UUID membershipId, ActorId actor);

    /**
     * Places the member in the role hierarchy, or out of it ({@code null}).
     *
     * @throws app.platformapi.ApiException {@code NOT_FOUND} for a role of another organization
     */
    void setRole(UUID membershipId, UUID roleId, ActorId actor);

    /**
     * Gives the member an access policy; a policy that needs a licence uses one from the pool.
     *
     * @throws app.platformapi.ApiException {@code NOT_FOUND} for a policy of another organization,
     *         {@code CONFLICT} when no licence is free
     */
    void assignPolicy(UUID membershipId, UUID policyId, ActorId actor);

    /** Takes an access policy from the member; its licence goes back. Nothing happens when they do not hold it. */
    void unassignPolicy(UUID membershipId, UUID policyId, ActorId actor);

    /**
     * Gives the member one ability directly.
     *
     * @param ability the ability key
     * @param reason a short note why (bounded, may be empty); kept for the people who manage access, never logged
     * @throws app.platformapi.ApiException {@code VALIDATION_ERROR} for an unknown ability
     */
    void grant(UUID membershipId, String ability, String reason, ActorId actor);

    /** Takes a directly given ability back. Nothing happens when the member does not hold it. */
    void revokeGrant(UUID membershipId, String ability, ActorId actor);

    /**
     * Checks that an administrator may give a new member the profile and the role, and returns the profile to store
     * (the default one when {@code profileId} is null). A caller who cannot manage access may only give a profile whose
     * abilities they all hold themselves, so nobody hands out more than they have.
     *
     * @throws app.platformapi.ApiException {@code VALIDATION_ERROR} for a profile or role of another organization or
     *         none, {@code FORBIDDEN} when the caller would give away what they do not have
     */
    UUID checkInvitation(UUID callerMembershipId, UUID profileId, UUID roleId);

    /** Profile names by identifier, for the invitation list. */
    Map<UUID, String> profileNames();

    /** Role names by identifier, for the invitation list. */
    Map<UUID, String> roleNames();
}
