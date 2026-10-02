package app.platform.licensing;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import java.util.List;

/**
 * Feature entitlements (ADR-0034): whether an organization has a feature, from its plan and from a platform
 * administrator's switch for that one organization. The one read contract other modules use: {@code
 * entitlements.enabled("approvals")}.
 *
 * <p>An entitlement says what the <em>organization</em> bought. It is independent of {@link Licences} (which members
 * hold
 * a licence) and of permissions (what a person may do): changing one never changes the others. Nothing is cached, so a
 * change is seen by the next call (staleness zero); Sprint 9 decides invalidation when a cache arrives.
 */
public interface Entitlements {

    /** Whether the organization of the current tenant context has the feature. An unknown feature is not enabled. */
    boolean enabled(String feature);

    /** Whether the organization has the feature. */
    boolean enabled(TenantId tenant, String feature);

    /** Every feature with its plan default, the override and the effective answer, for the console. */
    List<EntitlementView> of(TenantId tenant);

    /**
     * Switches a feature on or off for one organization over its plan, or removes the switch.
     *
     * @param enabled true or false to override, null to go back to the plan's default
     * @throws app.platformapi.ApiException {@code NOT_FOUND} for an unknown feature
     */
    void override(TenantId tenant, String feature, Boolean enabled, ActorId actor);
}
