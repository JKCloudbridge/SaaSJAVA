package app.webtest;

import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiResponse;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints that exist only in tests of the security layer. They report what the request context holds and raise the
 * security exceptions a method-level guard would, so the error handling can be proven without a real feature. Not part
 * of the application's component scan, the architecture rules or the OpenAPI document.
 */
@RestController
@RequestMapping("/api/v1/test/security")
public class IdentityTestController {

    private final TenantContexts contexts;

    public IdentityTestController(TenantContexts contexts) {
        this.contexts = contexts;
    }

    /** The tenant and the user of the request context, exactly as business code would read them. */
    @GetMapping("/context")
    public ApiResponse<Map<String, String>> context() {
        TenantContext context = contexts.current().orElse(null);
        return ApiResponse.of(Map.of(
                "tenantId", context == null ? "" : context.tenantId().toString(),
                "userId", context == null || context.userId() == null ? "" : context.userId().toString(),
                "membershipId", context == null || context.membershipId() == null ? ""
                        : context.membershipId().toString()));
    }

    /** What a method guard throws for a signed-in caller who may not do this. */
    @GetMapping("/denied")
    public ApiResponse<String> denied() {
        throw new AccessDeniedException("SECRET-INTERNAL detail of a policy");
    }

    /** What a method guard throws when the caller's authentication is not good enough. */
    @GetMapping("/unauthenticated")
    public ApiResponse<String> unauthenticated() {
        throw new BadCredentialsException("SECRET-INTERNAL detail of a credential");
    }
}
