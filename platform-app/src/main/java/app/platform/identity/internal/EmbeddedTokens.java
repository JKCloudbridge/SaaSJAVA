package app.platform.identity.internal;

import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.EmbeddedTokenView;
import app.platformapi.ErrorCode;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.stereotype.Service;

/**
 * Gives the page around a custom page's script an access token for that script (Sprint 18, ADR-0127). The token is
 * the signed-in member's own, narrowed: it belongs to the sign-in grant it was asked with and ends with it, it names
 * the organization of the host, it lives a few minutes ({@code platform.identity.tokens.embedded}), it is stored only as
 * a hash, and {@link EmbeddedAccessPolicy} limits what it can reach. It is not a new kind of login: nobody can sign in
 * with it, and it cannot be used to get another one.
 *
 * <p>Only a member of the organization of the host can ask, with a normal token; a token of a script cannot ask for a
 * new one (a page that is being shown is refreshed by the page around it, which holds the member's own session).
 */
@Service
class EmbeddedTokens {

    /** The start of every token of this kind; no other token is looked up as one (with a fallback, see Introspector). */
    static final String PREFIX = "pcs_";

    private final AuthorizationStore store;
    private final TenantContexts contexts;
    private final Counters counters;
    private final AuthAudit audit;
    private final Clock clock;
    private final IdentityProperties.Tokens settings;

    EmbeddedTokens(AuthorizationStore store, TenantContexts contexts, Counters counters, AuthAudit audit,
            Clock clock, IdentityProperties properties) {
        this.store = store;
        this.contexts = contexts;
        this.counters = counters;
        this.audit = audit;
        this.clock = clock;
        this.settings = properties.tokens();
    }

    /**
     * Issues a token.
     *
     * @param caller the authenticated caller of the request
     * @param customPage the API name of the page the token is for, written to the audit trail only; may be null
     * @throws ApiException {@code NOT_FOUND} on the platform host or for a caller that is no member,
     *         {@code FORBIDDEN} for the token of a script, {@code RATE_LIMITED} past the hourly allowance
     */
    EmbeddedTokenView issue(Principal caller, String customPage) {
        TenantContext context = contexts.current().filter(current -> current.membershipId() != null)
                .orElseThrow(() -> ApiException.notFound("This is not available at this address."));
        if (!(caller instanceof BearerTokenAuthentication token)
                || EmbeddedAccessFilter.isEmbedded(token)
                || !(token.getTokenAttributes().get("sid") instanceof String grant)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        UUID user = UUID.fromString(token.getName());
        UUID authorization = UUID.fromString(grant);
        Duration window = Duration.ofHours(1);
        if (counters.increment("embedded:" + user, window) > settings.embeddedPerHour()) {
            throw ApiException.rateLimited(window.toSeconds());
        }
        String secret = PREFIX + Hashes.randomSecret();
        Instant now = clock.instant();
        Instant ends = now.plus(settings.embedded());
        store.insertEmbedded(authorization, user, context.tenantId().value(), Hashes.hashed(secret), customPage, ends);
        audit.embeddedTokenIssued(user, customPage);
        return new EmbeddedTokenView(secret, ends);
    }
}
