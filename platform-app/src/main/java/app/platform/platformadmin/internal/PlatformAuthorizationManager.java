package app.platform.platformadmin.internal;

import app.platform.identity.PlatformRoles;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.UUID;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * The one rule for platform functions (ADR-0030, ADR-0052), applied by method security to every method marked
 * {@link PlatformFunction}: the request is on the platform host, a person is signed in, and the person holds one of the
 * roles the function names. It gives the answers the platform console always gave: {@code NOT_FOUND} on an organization
 * host (the console cannot be reached through an organization address), {@code UNAUTHENTICATED} without a person,
 * {@code FORBIDDEN} for a person without one of the roles, the last one recorded in the audit.
 *
 * <p>A platform role is not organization authority and never reads the tenant from a request: the decision is about the
 * person behind the token and the host that answered, nothing a client can name. Tenant-configurable decisions (what a
 * member of an organization may do) are not made here: they belong to the engine in the security module.
 */
@Component
class PlatformAuthorizationManager implements AuthorizationManager<MethodInvocation> {

    private final PlatformRoles roles;
    private final TenantContexts contexts;

    PlatformAuthorizationManager(PlatformRoles roles, TenantContexts contexts) {
        this.roles = roles;
        this.contexts = contexts;
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
            MethodInvocation invocation) {
        PlatformFunction function = AnnotatedElementUtils.findMergedAnnotation(invocation.getMethod(),
                PlatformFunction.class);
        if (function == null) {
            // The advisor only applies to marked methods; reaching here means the wiring is wrong: refuse.
            return new AuthorizationDecision(false);
        }
        if (contexts.current().isPresent()) {
            throw ApiException.notFound("This is not available at this address.");
        }
        roles.require(person(authentication.get()), function.value());
        return new AuthorizationDecision(true);
    }

    /** The signed-in person behind an authentication; {@code UNAUTHENTICATED} when there is none. */
    static UUID person(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
