package app.platform.approval.internal;

import app.platform.security.Ability;
import app.platform.security.Permissions;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The member behind the request (ADR-0152). Every endpoint of the member's side is answered on an organization host
 * only; on the platform host, or without a member, it is "not found", like the rest of the platform. The member is the
 * one the token and the host name, never one a request names. Abilities are asked here and nowhere else in this module.
 */
@Component
class ApprovalCaller {

    /**
     * The member who asks.
     *
     * @param userId the person
     * @param membershipId their membership of the organization
     */
    record Member(UUID userId, UUID membershipId) {
    }

    private final TenantContexts contexts;
    private final Permissions permissions;

    ApprovalCaller(TenantContexts contexts, Permissions permissions) {
        this.contexts = contexts;
        this.permissions = permissions;
    }

    /** The member of the request, or "not found" when there is none (the platform host, an anonymous request). */
    Member require() {
        TenantContext context = contexts.current().filter(current -> current.membershipId() != null).orElseThrow(
                () -> ApiException.notFound("This does not exist."));
        return new Member(context.userId(), context.membershipId());
    }

    boolean has(Member member, Ability ability) {
        return permissions.has(member.membershipId(), ability);
    }

    /** Refuses with FORBIDDEN unless the member holds the ability. */
    void require(Member member, Ability ability) {
        if (!has(member, ability)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }
}
