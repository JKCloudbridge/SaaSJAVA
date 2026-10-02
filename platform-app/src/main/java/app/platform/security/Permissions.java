package app.platform.security;

import java.util.Set;
import java.util.UUID;

/**
 * The one read contract other modules use to ask what a member may do (ADR-0040). It answers for the organization the
 * thread's tenant context names (never one named by a caller) and reads the current state every time: there is no
 * cache, so a change takes effect at once. Sprint 9 decides whether a cache and its invalidation are worth adding.
 *
 * <p>The backend is the only place that decides: a screen that hides a button is a convenience, not a check. Every
 * action that needs an ability asks here, inside the transaction that does the work, so the answer and the action
 * cannot drift apart. The tenant context must be open before the transaction begins (see {@code TenantContexts}).
 *
 * <p>A member who does not exist in the organization has no abilities (the answer is the same as for a member with
 * nothing), so asking never reveals whether a membership exists.
 */
public interface Permissions {

    /**
     * The abilities the member has now: profile (while licensed) plus access policies (also those given to their
     * groups,
     * a licence-bound one while they hold its licence) plus individual grants.
     */
    Set<Ability> effective(UUID membershipId);

    /**
     * What the member may do with data now (permissions on objects and fields): the same union as {@link #effective}
     * (ADR-0049). The decision API ({@link Decisions}) is the way to ask about one object or field; this is the whole
     * matrix, for the screens that show it.
     */
    DataAccess data(UUID membershipId);

    /** Whether the member has the ability now. */
    default boolean has(UUID membershipId, Ability ability) {
        return effective(membershipId).contains(ability);
    }
}
