package app.platform.publicapi.internal;

import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who is calling the public API: the member the host and the token name, from the tenant context, never from the
 * request (Sprint 30, ADR-0244). It decides nothing about what the member may do; that is the record store's work, for
 * the public door exactly as for the internal one.
 */
@Component
class PublicCaller {

    private final TenantContexts contexts;

    PublicCaller(TenantContexts contexts) {
        this.contexts = contexts;
    }

    /**
     * The membership of the caller in the organization the host names.
     *
     * @throws ApiException {@code NOT_FOUND} where there is no organization or no member (the platform host), in the
     *         words of an unknown organization
     */
    UUID membership() {
        return contexts.current().map(TenantContext::membershipId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, PublicApiInterceptor.NO_ORGANIZATION));
    }
}
