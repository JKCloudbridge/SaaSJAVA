package app.platform.platformadmin.internal;

import app.platform.identity.PlatformRole;
import app.platform.identity.PlatformRoles;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.security.Principal;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who is asking, for every platform endpoint (ADR-0030): the signed-in person behind the request, on the platform host
 * only, holding one of the roles the action needs. Platform endpoints are answered on the platform host alone, exactly
 * like founding an organization: on an organization host they are {@code NOT_FOUND}, so the console cannot be reached
 * through an organization address. A platform role is not organization authority and never reads the tenant from the
 * request: an endpoint that names an organization names a destination chosen by an authorized platform person, not the
 * tenant of the request.
 */
@Component
class PlatformCaller {

    private final PlatformRoles roles;
    private final TenantContexts contexts;

    PlatformCaller(PlatformRoles roles, TenantContexts contexts) {
        this.roles = roles;
        this.contexts = contexts;
    }

    /**
     * Checks the host and the role, and returns the person.
     *
     * @throws ApiException {@code NOT_FOUND} on an organization host, {@code UNAUTHENTICATED} without a person,
     *         {@code FORBIDDEN} (audited) for a person without one of the roles
     */
    UUID require(Principal principal, PlatformRole... anyOf) {
        if (contexts.current().isPresent()) {
            throw ApiException.notFound("This is not available at this address.");
        }
        UUID user = userOf(principal);
        roles.require(user, anyOf);
        return user;
    }

    private static UUID userOf(Principal principal) {
        if (principal == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
