package app.platform.tenant;

import java.util.Set;

/**
 * Where an organization is in its life (ADR-0014).
 *
 * <pre>
 * PROVISIONING --> ACTIVE &lt;--&gt; SUSPENDED
 *      |             |             |
 *      +-------------+-------------+--> DEACTIVATED (final)
 * </pre>
 *
 * Only {@link #ACTIVE} organizations accept requests. The database enforces the same transitions
 * ({@code platform_tenant_status_guard}); a test proves that both agree for every pair of states.
 */
public enum TenantStatus {

    /** Created, not yet open: its initial setup is still running. */
    PROVISIONING,

    /** Open for use. */
    ACTIVE,

    /** Closed temporarily (for example unpaid or under investigation); can be reinstated. */
    SUSPENDED,

    /** Closed for good. No way back; the data is handled by the retention rules (Sprints 14 and 34). */
    DEACTIVATED;

    /** Whether the organization accepts requests. */
    public boolean isOpen() {
        return this == ACTIVE;
    }

    /** The states this one may move to; empty for the final state. */
    public Set<TenantStatus> allowedTargets() {
        return switch (this) {
            case PROVISIONING -> Set.of(ACTIVE, DEACTIVATED);
            case ACTIVE -> Set.of(SUSPENDED, DEACTIVATED);
            case SUSPENDED -> Set.of(ACTIVE, DEACTIVATED);
            case DEACTIVATED -> Set.of();
        };
    }

    /** Whether the move from this state to {@code target} is legal. Staying in the same state is not a move. */
    public boolean canTransitionTo(TenantStatus target) {
        return allowedTargets().contains(target);
    }
}
