package app.platform.identity.internal;

import app.platform.licensing.Entitlements;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Proves who a connected application is at the token endpoint (Sprint 31, ADR-0268). It replaces the library's check of
 * a client secret, because an application can hold several secrets at once (a rotation overlaps), a secret is only a
 * hash here, and every refusal must look the same.
 *
 * <p>The order is fixed: the switch, the limit of failed attempts, the application (found only on the host of its
 * organization), then the secrets that work now, all compared in constant time, and only after a secret matched are the
 * state, the member and the plan feature judged. An unknown application, a wrong secret, a disabled application, an
 * application on the wrong host, a member who can no longer act, a plan without the feature and the switch being off
 * all end in the one answer {@code invalid_client}; the true reason is audited for an application that exists (at most
 * once for each window) and never said to the caller. Only a caller who has used up the limit is told to wait.
 */
final class ClientAuthenticationProvider implements AuthenticationProvider {

    /** The error code of a caller who has used up the failed attempts; the handler answers it with 429. */
    static final String LIMITED = "temporarily_unavailable";

    private static final String FEATURE = "integrations";

    private final ClientStore store;
    private final TransactionTemplate transaction;
    private final ClientAuthLimiter limiter;
    private final Counters counters;
    private final ClientProperties properties;
    private final Entitlements entitlements;
    private final MembershipGate gate;
    private final AuthAudit audit;
    private final MeterRegistry meters;
    private final boolean trustForwardedFor;
    private final Clock clock;

    ClientAuthenticationProvider(ClientStore store, TransactionTemplate transaction, ClientAuthLimiter limiter,
            Counters counters, ClientProperties properties, Entitlements entitlements, MembershipGate gate,
            AuthAudit audit, MeterRegistry meters, boolean trustForwardedFor, Clock clock) {
        this.store = store;
        this.transaction = transaction;
        this.limiter = limiter;
        this.counters = counters;
        this.properties = properties;
        this.entitlements = entitlements;
        this.gate = gate;
        this.audit = audit;
        this.meters = meters;
        this.trustForwardedFor = trustForwardedFor;
        this.clock = clock;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) authentication;
        ClientAuthenticationMethod method = token.getClientAuthenticationMethod();
        if (!ClientAuthenticationMethod.CLIENT_SECRET_BASIC.equals(method)
                && !ClientAuthenticationMethod.CLIENT_SECRET_POST.equals(method)) {
            // Another kind of client (the web application's own, which holds no secret): not ours to judge.
            return null;
        }
        String clientId = String.valueOf(token.getPrincipal());
        String presented = token.getCredentials() == null ? null : token.getCredentials().toString();
        String source = source();

        if (!properties.enabled()) {
            ClientSecrets.matchesNothing(presented);
            throw refused(null, clientId, "switched_off", source, false);
        }
        if (!limiter.allowed(source, clientId)) {
            meters.counter("platform.clients.auth.failures", "reason", "limited").increment();
            throw new OAuth2AuthenticationException(new OAuth2Error(LIMITED));
        }
        ClientStore.ClientRow row = transaction.execute(status -> store.findByClientId(clientId)).orElse(null);
        if (row == null) {
            ClientSecrets.matchesNothing(presented);
            throw refused(null, clientId, "unknown_client", source, false);
        }
        List<ClientStore.SecretRow> working = transaction.execute(status -> store.workingSecrets(row.id()));
        ClientStore.SecretRow matched = null;
        // Every working secret is compared, whichever matches first, so the time does not say which one it was.
        for (ClientStore.SecretRow secret : working) {
            if (ClientSecrets.matches(presented, secret.hash()) && secret.works(clock.instant())) {
                matched = secret;
            }
        }
        if (working.isEmpty()) {
            ClientSecrets.matchesNothing(presented);
        }
        if (matched == null) {
            throw refused(row, clientId, "wrong_secret", source, true);
        }
        if (!row.active()) {
            throw refused(row, clientId, "disabled", source, true);
        }
        if (!row.memberCanAct() || gate.activeMembership(row.userId()).isEmpty()) {
            throw refused(row, clientId, "member_not_active", source, true);
        }
        if (!entitlements.enabled(FEATURE)) {
            throw refused(row, clientId, "feature_off", source, true);
        }
        OAuth2ClientAuthenticationToken result = new OAuth2ClientAuthenticationToken(
                PersistentRegisteredClients.build(row), method, presented);
        result.setDetails(new AuthorizationStore.ClientAuthentication(matched.id()));
        return result;
    }

    /** One refusal: counted, audited for a known application, and the same words for every cause. */
    private OAuth2AuthenticationException refused(ClientStore.ClientRow row, String clientId, String reason,
            String source, boolean known) {
        limiter.recordFailure(source, clientId);
        meters.counter("platform.clients.auth.failures", "reason", reason).increment();
        if (known && row != null && counters.increment("cauditonce:" + row.id() + ":" + reason,
                limiter.retryAfter()) == 1) {
            audit.clientTokenRefused(row.userId(), row.clientId(), reason, source);
        }
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT));
    }

    private String source() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return ClientSource.of(request, trustForwardedFor);
        }
        return "unknown";
    }
}
