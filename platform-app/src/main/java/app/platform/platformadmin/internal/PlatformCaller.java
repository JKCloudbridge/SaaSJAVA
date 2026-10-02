package app.platform.platformadmin.internal;

import java.security.Principal;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Who is asking, for the endpoints of the platform console (ADR-0030): the signed-in person behind the request. Whether
 * that person may use the endpoint at all (the platform host, a platform role) is decided before the method runs, by
 * {@link PlatformAuthorizationManager} through the {@link PlatformFunction} annotation (ADR-0052); this only names the
 * person for the audit record and the action. A platform role is not organization authority and never reads the tenant
 * from the request: an endpoint that names an organization names a destination chosen by an authorized platform
 * person, not the tenant of the request.
 */
@Component
class PlatformCaller {

    /**
     * The person behind the request.
     *
     * @throws app.platformapi.ApiException {@code UNAUTHENTICATED} without a person
     */
    UUID person(Principal principal) {
        if (principal instanceof Authentication authentication) {
            return PlatformAuthorizationManager.person(authentication);
        }
        return PlatformAuthorizationManager.person(null);
    }
}
