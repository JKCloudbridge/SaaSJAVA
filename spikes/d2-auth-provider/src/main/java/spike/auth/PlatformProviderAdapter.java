package spike.auth;

import java.util.List;
import java.util.function.Consumer;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.AuthorityUtils;
import spike.auth.AuthenticationAttempt.ExternalAssertionAttempt;
import spike.auth.AuthenticationAttempt.PasswordAttempt;
import spike.auth.AuthenticationOutcome.Authenticated;
import spike.auth.AuthenticationOutcome.ChallengeRequired;
import spike.auth.AuthenticationOutcome.Rejected;

/**
 * The single bridge between the platform abstraction and Spring Security. Everything the outside world
 * sees on failure is the same {@link BadCredentialsException} with the same message (no account
 * enumeration); the precise reason goes to the audit sink.
 */
public class PlatformProviderAdapter implements AuthenticationProvider {

    public static final String UNIFORM_MESSAGE = "Bad credentials";

    /** Token for an assertion already verified by a federation layer. */
    public static final class ExternalAssertionToken extends AbstractAuthenticationToken {
        private final String issuer;
        private final String subject;

        public ExternalAssertionToken(String issuer, String subject) {
            super(AuthorityUtils.NO_AUTHORITIES);
            this.issuer = issuer;
            this.subject = subject;
        }

        @Override
        public Object getCredentials() {
            return issuer;
        }

        @Override
        public Object getPrincipal() {
            return subject;
        }
    }

    /** Raised when a provider demands a further factor. Distinct type so the login flow can branch. */
    public static class ChallengeRequiredException extends AuthenticationException {
        public ChallengeRequiredException(String challengeType) {
            super("Additional authentication required: " + challengeType);
        }
    }

    public record AuditEntry(String providerId, AuthenticationOutcome.Reason reason) {
    }

    private final List<PlatformAuthenticationProvider> providers;
    private final Consumer<AuditEntry> audit;

    public PlatformProviderAdapter(List<PlatformAuthenticationProvider> providers, Consumer<AuditEntry> audit) {
        this.providers = List.copyOf(providers);
        this.audit = audit;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        AuthenticationAttempt attempt = toAttempt(authentication);
        for (PlatformAuthenticationProvider provider : providers) {
            if (!provider.supports(attempt)) {
                continue;
            }
            AuthenticationOutcome outcome = provider.authenticate(attempt);
            return switch (outcome) {
                case Authenticated a -> UsernamePasswordAuthenticationToken.authenticated(
                        a.userId(), null, AuthorityUtils.NO_AUTHORITIES);
                case Rejected r -> {
                    audit.accept(new AuditEntry(provider.id(), r.reason()));
                    throw new BadCredentialsException(UNIFORM_MESSAGE);
                }
                case ChallengeRequired c -> throw new ChallengeRequiredException(c.challengeType());
            };
        }
        throw new BadCredentialsException(UNIFORM_MESSAGE);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication)
                || ExternalAssertionToken.class.isAssignableFrom(authentication);
    }

    private static AuthenticationAttempt toAttempt(Authentication authentication) {
        if (authentication instanceof ExternalAssertionToken t) {
            return new ExternalAssertionAttempt(String.valueOf(t.getCredentials()), String.valueOf(t.getPrincipal()));
        }
        return new PasswordAttempt(String.valueOf(authentication.getPrincipal()),
                String.valueOf(authentication.getCredentials()));
    }
}
