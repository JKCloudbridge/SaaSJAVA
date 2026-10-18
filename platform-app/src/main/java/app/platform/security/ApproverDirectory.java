package app.platform.security;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The people an approval rule can name (Sprint 24, ADR-0146): who holds a profile, who is placed in a role, who holds
 * the
 * parent role of a person's role, and the facts about given people. It answers the four questions the approver kinds
 * need and nothing else; it does not decide what anybody may do (that is {@link Permissions} and {@link Decisions}) and
 * it
 * never reads the role hierarchy for an ability (Sprint 7).
 *
 * <p>A candidate is a member of this organization that holds what the rule names. Whether the person is an
 * <em>active</em> member is the identity module's fact (a deactivated member keeps their recorded profile until they
 * return, ADR-0045), so the caller filters with the reference directory of users; this contract says only whether the
 * member holds the licence their profile needs, which is what makes a profile count for anything (ADR-0039).
 *
 * <p>Every method answers for the organization the thread's tenant context names, in the caller's transaction.
 */
public interface ApproverDirectory {

    /**
     * One member a rule could name.
     *
     * @param membershipId the membership
     * @param userId the user behind it
     * @param licensed whether the member holds the licence the profile they hold needs
     */
    record Candidate(UUID membershipId, UUID userId, boolean licensed) {
    }

    /**
     * A profile, a role or a public group by name, so a screen can offer them as the choice of a rule.
     *
     * @param id the identifier
     * @param name the name the organization gave it
     */
    record Named(UUID id, String name) {
    }

    /** The profiles of the organization. */
    List<Named> profiles();

    /** The roles of the organization. */
    List<Named> roles();

    /** The public groups of the organization. */
    List<Named> groups();

    /** The members who hold the profile. */
    List<Candidate> holdersOfProfile(UUID profileId);

    /** The members placed in the role itself (not in the roles below it). */
    List<Candidate> holdersOfRole(UUID roleId);

    /**
     * The members placed in the parent of the role the user is placed in; empty when the user has no role or the role
     * has
     * no parent. This is the "manager" of a record's owner.
     */
    List<Candidate> parentRoleHolders(UUID userId);

    /** The members behind these users; a user who is not a member is not in the answer. */
    List<Candidate> ofUsers(Collection<UUID> userIds);

    /** The members with these memberships (a public group lists memberships). */
    List<Candidate> ofMemberships(Collection<UUID> membershipIds);

    /** The member behind the user, if there is one. */
    default Optional<Candidate> ofUser(UUID userId) {
        return ofUsers(List.of(userId)).stream().findFirst();
    }
}
