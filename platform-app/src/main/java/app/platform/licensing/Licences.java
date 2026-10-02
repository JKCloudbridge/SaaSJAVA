package app.platform.licensing;

import app.platform.sharedkernel.ActorId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The licences of the organization of the current tenant context (ADR-0032): pools by licence type and one assignment
 * per member who holds a licence. Every method works for the organization the thread's tenant context names, never one
 * named by a caller; row level security and the database guards back that up.
 *
 * <p>A licence only counts and limits <em>assignments</em>. It grants no permission and switches no feature on: those
 * are
 * decided by access policies (Sprint 7) and by {@link Entitlements}. The three stay separate mechanisms.
 *
 * <p>The methods run in the caller's transaction when there is one and in their own otherwise. The tenant context must
 * be
 * open before the transaction begins (see {@code TenantContexts}). No caller checks who may ask: the identity module
 * asks
 * "may this member administer?" before it calls (ADR-0026), and platform administration asks for a platform role.
 */
public interface Licences {

    /** The pools of the organization with their numbers, by licence type. */
    List<PoolView> pools();

    /** The licence type key each licensed member holds, by membership identifier. Members without one are absent. */
    Map<UUID, String> assigned();

    /**
     * Gives an active member a licence of the type, moving them from another type if they hold one. The pool row is
     * locked, so two callers assigning the last free licence have one winner.
     *
     * @throws app.platformapi.ApiException {@code NOT_FOUND} for an unknown licence type, {@code CONFLICT} when the
     *         organization has no pool of the type, none is free, or the member is not active
     */
    void assign(UUID membershipId, String licenceType, ActorId actor);

    /**
     * Takes the member's licence back (nothing happens when they hold none).
     *
     * @return whether the member held one
     */
    boolean release(UUID membershipId, ActorId actor);

    /**
     * Gives the member the default licence type when one is free; never fails for lack of one (a member without a
     * licence is shown as unlicensed, ADR-0032). Called when a membership becomes active.
     *
     * @return whether a licence was assigned
     */
    boolean assignDefault(UUID membershipId, ActorId actor);

    /**
     * Sets the size of a pool (a platform administrator's action, run in that one organization's context).
     *
     * @throws app.platformapi.ApiException {@code CONFLICT} when the new quantity is below the licences in use,
     *         {@code NOT_FOUND} for an unknown licence type, {@code VALIDATION_ERROR} for a quantity out of range
     */
    void setPoolQuantity(String licenceType, int quantity, ActorId actor);
}
