package app.platform.identity.internal;

import app.platform.sharedkernel.ActorId;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Ends sessions and tokens for good: the sign-in sessions and the grants (with all their tokens) of one user. The
 * security version makes them useless on its own, because every check compares it; revoking the rows as well keeps the
 * tables truthful, makes the end visible in the audit trail and lets the rows be listed and cleaned up.
 */
@Component
class SessionRevocation {

    private final LoginSessions loginSessions;
    private final AuthorizationStore authorizations;
    private final AuthAudit audit;

    SessionRevocation(LoginSessions loginSessions, AuthorizationStore authorizations, AuthAudit audit) {
        this.loginSessions = loginSessions;
        this.authorizations = authorizations;
        this.audit = audit;
    }

    /**
     * Revokes everything of a user.
     *
     * @param reason a lower-case code for the audit record, for example {@code password_changed}
     * @return how many sessions and grants were alive
     */
    int revokeAll(UUID userId, String reason, ActorId actor) {
        int count = loginSessions.revokeAll(userId, actor) + authorizations.revokeAll(userId, reason, actor.value());
        audit.sessionRevoked(userId, reason, count);
        return count;
    }

    /**
     * Ends everything a user holds for one organization: the sign-in sessions and the grants bound to that
     * organization's host. Used when a membership ends (ADR-0026); the person's other organizations keep working.
     *
     * @return how many sessions and grants were alive
     */
    int revokeAllIn(UUID userId, UUID tenantId, String reason, ActorId actor) {
        int count = loginSessions.revokeAllIn(userId, tenantId, actor)
                + authorizations.revokeAllIn(userId, tenantId, reason, actor.value());
        audit.sessionRevoked(userId, reason, count);
        return count;
    }
}
