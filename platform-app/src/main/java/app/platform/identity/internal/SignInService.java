package app.platform.identity.internal;

import app.platform.identity.AuthenticationAttempt;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

/**
 * The first half of a sign-in: from "address and password" to "a login session" (stories S3-SEC-05 to S3-SEC-11).
 *
 * <p>Order of events: the rate limits decide whether the attempt is looked at at all; the provider abstraction (through
 * Spring Security's authentication manager and the one adapter) decides who the person is; the outcome becomes either a
 * login session or the one uniform failure. Every refusal, every failure and every success is audited with its true
 * reason, by the adapter and the limiter bookkeeping here; nothing the caller can see depends on that reason.
 */
@Service
class SignInService {

    private static final Logger LOG = LoggerFactory.getLogger(SignInService.class);

    /** The one failure message of a sign-in, whatever the reason. */
    static final String FAILURE_MESSAGE = "The email address or the password is not correct.";

    private final SignInLimiter limiter;
    private final AuthenticationManager authentication;
    private final UserRepository users;
    private final LoginSessions loginSessions;
    private final AuthAudit audit;
    private final TenantContexts contexts;
    private final MembershipGate gate;
    private final PlatformRoleRepository platformRoles;
    private final Duration sessionLifetime;

    SignInService(SignInLimiter limiter, PlatformProviderAdapter adapter, UserRepository users,
            LoginSessions loginSessions, AuthAudit audit, TenantContexts contexts, MembershipGate gate,
            PlatformRoleRepository platformRoles, IdentityProperties properties) {
        this.platformRoles = platformRoles;
        this.limiter = limiter;
        // The manager is private to this class: the adapter is the only way a sign-in reaches a provider.
        this.authentication = new ProviderManager(adapter);
        this.users = users;
        this.loginSessions = loginSessions;
        this.audit = audit;
        this.contexts = contexts;
        this.gate = gate;
        this.sessionLifetime = properties.tokens().loginSession();
    }

    /**
     * Checks the credentials and opens a login session.
     *
     * @param identifier the address as typed
     * @param password the password as typed; cleared before this method returns
     * @param source the normalized network source of the request
     * @return the secret of the new login session, to be set as a cookie
     * @throws ApiException {@code RATE_LIMITED}, {@code SERVICE_UNAVAILABLE}, or {@code UNAUTHENTICATED} with the same
     *         message for every kind of failure
     */
    String signIn(String identifier, char[] password, String source) {
        try {
            SignInLimiter.Verdict verdict = limiter.admit(source, identifier);
            if (!verdict.allowed()) {
                audit.rateLimited(verdict.name().toLowerCase(java.util.Locale.ROOT), source);
                throw ApiException.rateLimited(Math.max(1, limiter.retryAfter(verdict).toSeconds()));
            }
            Authentication result;
            try {
                result = authentication.authenticate(new PlatformProviderAdapter.AttemptToken(
                        new AuthenticationAttempt.PasswordAttempt(identifier, password, source), identifier));
            } catch (AuthenticationServiceException e) {
                // The provider could not decide (no hashing capacity): not a verdict on the person.
                throw ApiException.unavailable(1);
            } catch (AuthenticationException e) {
                limiter.recordFailure(source);
                throw new ApiException(ErrorCode.UNAUTHENTICATED, FAILURE_MESSAGE);
            }
            UUID userId = UUID.fromString(result.getName());
            long version = users.findById(userId).map(user -> user.securityVersion())
                    .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, FAILURE_MESSAGE));
            if (gate.onOrganizationHost() && gate.activeMembership(userId).isEmpty()) {
                // Right password, but not a member of this organization (ADR-0026): the same failure as a wrong
                // password, with the true reason in the audit record only. The password check has already run, so
                // the work done does not depend on whether the person belongs here.
                audit.signInFailed(userId, identifier, "local", "not_a_member", source);
                limiter.recordFailure(source);
                throw new ApiException(ErrorCode.UNAUTHENTICATED, FAILURE_MESSAGE);
            }
            UUID boundTenant = contexts.current().map(TenantContext::tenantId).map(tenant -> tenant.value())
                    .orElse(null);
            LOG.info("Sign-in succeeded");
            if (boundTenant == null) {
                // The platform host: a person who holds platform roles is recorded loudly (ADR-0030).
                List<String> roles = platformRoles.rolesOf(userId).stream().map(Enum::name).sorted().toList();
                if (!roles.isEmpty()) {
                    audit.platformSignIn(userId, roles, source);
                }
            }
            return loginSessions.create(userId, boundTenant, version, sessionLifetime);
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
