package app.platform.licensing;

import app.platform.sharedkernel.ActorId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The licences of the organization of the current tenant context (ADR-0032, ADR-0039): pools by licence type and the
 * assignments that use them. A member holds at most one licence <em>for their profile</em> (of the profile's licence
 * type) and at most one licence for each licence-bound access policy assigned to them. Every method works for the
 * organization the thread's tenant context names, never one named by a caller; row level security and the database
 * guards back that up.
 *
 * <p>A licence only counts and limits <em>assignments</em>. It grants no permission and switches no feature on: those
 * are decided by profiles and access policies (the security module) and by {@link Entitlements}. The three stay
 * separate mechanisms. Who may ask is not decided here: the identity and security modules ask "may this member?" before
 * they call (ADR-0026, ADR-0039), and platform administration asks for a platform role.
 *
 * <p>The methods run in the caller's transaction when there is one and in their own otherwise. The tenant context must
 * be open before the transaction begins (see {@code TenantContexts}). Every method that changes an assignment first
 * takes the organization's access lock, so the order of locks is always the same (access lock, member row, pool row)
 * and two transactions cannot wait for each other.
 */
public interface Licences {

    /** The identifier of the licence type with the key (the platform-level catalogue), or empty for an unknown key. */
    Optional<UUID> licenceTypeId(String key);

    /** Every licence type of the catalogue, by key. */
    List<LicenceTypeView> licenceTypes();

    /** The key of every licence type of the catalogue, by identifier. */
    Map<UUID, String> licenceTypeKeys();

    /** The pools of the organization with their numbers, by licence type. */
    List<PoolView> pools();

    /** The licence type key each member holds for their profile, by membership identifier. Absent: none held. */
    Map<UUID, String> assigned();

    /** The licence type key the member holds for their profile, if any. */
    Optional<String> profileLicenceOf(UUID membershipId);

    /** How many members hold a licence for each access policy, by policy identifier. Policies with none are absent. */
    Map<UUID, Integer> policyUses();

    /**
     * Gives an active member the licence for their profile, moving them from another type if they hold one. The pool
     * row is locked, so two callers assigning the last free licence have one winner.
     *
     * @throws app.platformapi.ApiException {@code NOT_FOUND} for an unknown licence type, {@code CONFLICT} when the
     *         organization has no pool of the type, none is free, or the member is not active
     */
    void assign(UUID membershipId, String licenceType, ActorId actor);

    /**
     * Gives the member the licence for their profile when one is free; never fails for lack of one (a member without a
     * licence is shown as unlicensed and their profile gives no abilities until one is free, ADR-0039). Called when a
     * membership becomes active. A member who holds a licence of another type keeps it.
     *
     * @return whether the member now holds a licence of the type
     */
    boolean assignIfFree(UUID membershipId, String licenceType, ActorId actor);

    /**
     * Like {@link #assignIfFree} for the first administrator of an organization, who must be able to administer it:
     * when the organization has no pool of the type, or it is used up, the pool grows by one (a platform administrator
     * sees and can change it). Never fails.
     */
    void assignToFounder(UUID membershipId, String licenceType, ActorId actor);

    /**
     * Gives an active member the licence for one licence-bound access policy.
     *
     * @throws app.platformapi.ApiException {@code NOT_FOUND} for an unknown licence type, {@code CONFLICT} when the
     *         organization has no pool of the type, none is free, or the member is not active
     */
    void assignForPolicy(UUID membershipId, String licenceType, UUID policyId, ActorId actor);

    /**
     * Takes the member's licence for the access policy back.
     *
     * @return whether the member held one
     */
    boolean releaseForPolicy(UUID membershipId, UUID policyId, ActorId actor);

    /**
     * Takes the member's licence for their profile back (nothing happens when they hold none).
     *
     * @return whether the member held one
     */
    boolean release(UUID membershipId, ActorId actor);

    /**
     * Takes every licence of the member back, for the profile and for access policies (a member leaves or is
     * deactivated).
     *
     * @return how many were held
     */
    int releaseAll(UUID membershipId, ActorId actor);

    /**
     * Sets the size of a pool (a platform administrator's action, run in that one organization's context).
     *
     * @throws app.platformapi.ApiException {@code CONFLICT} when the new quantity is below the licences in use,
     *         {@code NOT_FOUND} for an unknown licence type, {@code VALIDATION_ERROR} for a quantity out of range
     */
    void setPoolQuantity(String licenceType, int quantity, ActorId actor);
}
