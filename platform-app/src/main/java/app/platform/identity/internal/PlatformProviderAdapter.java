package app.platform.identity.internal;

import app.platform.identity.AuthenticationAttempt;
import app.platform.identity.AuthenticationOutcome;
import app.platform.identity.PlatformAuthenticationProvider;
import app.platform.identity.RejectionReason;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.AuthorityUtils;

/**
 * The one bridge between the platform's provider abstraction and Spring Security (story S3-SEC-19, ADR-0005).
 * Everything Spring Security (and so the rest of the platform) sees on a failure is the same {@link
 * BadCredentialsException} with the same text, whatever the provider decided; the true reason goes to the audit trail
 * only (stories S3-SEC-05 and S3-SEC-11).
 *
 * <p>A new provider (an external identity provider, a second factor) needs no change here.
 */
final class PlatformProviderAdapter implements AuthenticationProvider {

    /** The one message of every failed authentication. */
    static final String UNIFORM_MESSAGE = "Bad credentials";

    /** What the adapter hands to the authentication manager: an attempt, not yet decided. */
    static final class AttemptToken extends AbstractAuthenticationToken {

        private static final long serialVersionUID = 1L;

        private final transient AuthenticationAttempt attempt;
        private final String identifier;

        AttemptToken(AuthenticationAttempt attempt, String identifier) {
            super(AuthorityUtils.NO_AUTHORITIES);
            this.attempt = attempt;
            this.identifier = identifier;
        }

        AuthenticationAttempt attempt() {
            return attempt;
        }

        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public Object getPrincipal() {
            return identifier;
        }
    }

    /**
     * A provider asked for one more step. Its own type so that a sign-in flow can branch on it later; until a second
     * factor exists the caller still receives the uniform answer.
     */
    static final class ChallengeRequiredException extends AuthenticationException {

        private static final long serialVersionUID = 1L;

        ChallengeRequiredException() {
            super(UNIFORM_MESSAGE);
        }
    }

    private final List<PlatformAuthenticationProvider> providers;
    private final AuthAudit audit;

    PlatformProviderAdapter(List<PlatformAuthenticationProvider> providers, AuthAudit audit) {
        this.providers = List.copyOf(providers);
        this.audit = audit;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        AttemptToken token = (AttemptToken) authentication;
        AuthenticationAttempt attempt = token.attempt();
        String source = attempt instanceof AuthenticationAttempt.PasswordAttempt password ? password.source() : "";
        for (PlatformAuthenticationProvider provider : providers) {
            if (!provider.supports(attempt)) {
                continue;
            }
            return switch (provider.authenticate(attempt)) {
                case AuthenticationOutcome.Authenticated authenticated -> {
                    audit.signInSucceeded(authenticated.userId(), provider.id(), source);
                    yield UsernamePasswordAuthenticationToken.authenticated(
                            authenticated.userId().toString(), null, AuthorityUtils.NO_AUTHORITIES);
                }
                case AuthenticationOutcome.Rejected rejected -> {
                    audit.signInFailed(rejected.userId(), String.valueOf(token.getPrincipal()), provider.id(),
                            rejected.reason().code(), source);
                    if (rejected.reason() == RejectionReason.UNAVAILABLE) {
                        throw new AuthenticationServiceException(UNIFORM_MESSAGE);
                    }
                    throw new BadCredentialsException(UNIFORM_MESSAGE);
                }
                case AuthenticationOutcome.ChallengeRequired challenge -> {
                    audit.signInFailed(challenge.userId(), String.valueOf(token.getPrincipal()), provider.id(),
                            "challenge_required", source);
                    throw new ChallengeRequiredException();
                }
            };
        }
        audit.signInFailed(null, String.valueOf(token.getPrincipal()), "none", "no_provider", source);
        throw new BadCredentialsException(UNIFORM_MESSAGE);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return AttemptToken.class.isAssignableFrom(authentication);
    }
}
