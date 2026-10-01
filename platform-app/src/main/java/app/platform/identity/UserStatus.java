package app.platform.identity;

import java.util.Set;

/**
 * Where a user is in their life (ADR-0019).
 *
 * <pre>
 * INVITED --> ACTIVE &lt;--&gt; SUSPENDED
 *    |           |             |
 *    +-----------+-------------+--> DEACTIVATED (final)
 * </pre>
 *
 * Only {@link #ACTIVE} users can sign in or use a token. The database enforces the same transitions
 * ({@code platform_user_status_guard}); a test proves that both agree for every pair of states.
 */
public enum UserStatus {

    /** The identity exists but is not yet usable: activation (verification, invitation) is not complete. */
    INVITED,

    /** Can sign in. */
    ACTIVE,

    /** Closed temporarily (for example under investigation); every token stops working; can be reinstated. */
    SUSPENDED,

    /** Closed for good. No way back. */
    DEACTIVATED;

    /** Whether the user may sign in and use tokens. */
    public boolean canSignIn() {
        return this == ACTIVE;
    }

    /** The states this one may move to; empty for the final state. */
    public Set<UserStatus> allowedTargets() {
        return switch (this) {
            case INVITED -> Set.of(ACTIVE, DEACTIVATED);
            case ACTIVE -> Set.of(SUSPENDED, DEACTIVATED);
            case SUSPENDED -> Set.of(ACTIVE, DEACTIVATED);
            case DEACTIVATED -> Set.of();
        };
    }

    /** Whether the move from this state to {@code target} is legal. Staying in the same state is not a move. */
    public boolean canTransitionTo(UserStatus target) {
        return allowedTargets().contains(target);
    }
}
