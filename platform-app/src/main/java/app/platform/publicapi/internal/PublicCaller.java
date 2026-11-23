package app.platform.publicapi.internal;

import app.platform.sharedkernel.access.ConnectedAppCaller;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who is calling the public API: the member the host and the token name, from the tenant context, never from the
 * request (Sprint 30, ADR-0244). It decides nothing about what the member may do; that is the record store's work, for
 * the public door exactly as for the internal one.
 *
 * <p>Since Sprint 31 a request may come through a connected application, which acts as one member. The member is the
 * same; what differs is the key space of repeat-safe writes: two applications that act as one member must not share a
 * key, so a keyed write is claimed under a name made of the member and the application (ADR-0273).
 */
@Component
class PublicCaller {

    private final TenantContexts contexts;
    private final ConnectedAppCaller connectedApp;

    PublicCaller(TenantContexts contexts, ConnectedAppCaller connectedApp) {
        this.contexts = contexts;
        this.connectedApp = connectedApp;
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

    /**
     * The name a keyed write is claimed under: the member for a person's own token (as it always was, so nothing
     * already stored changes meaning), and a fixed name made of the member and the application for a connected
     * application.
     */
    UUID idempotencyKeySpace() {
        UUID member = membership();
        return connectedApp.client()
                .map(client -> UUID.nameUUIDFromBytes((member + ":" + client).getBytes(StandardCharsets.UTF_8)))
                .orElse(member);
    }
}
