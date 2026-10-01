package spike.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import spike.auth.AuthenticationOutcome.Reason;
import spike.auth.PlatformProviderAdapter.AuditEntry;
import spike.auth.PlatformProviderAdapter.ChallengeRequiredException;
import spike.auth.PlatformProviderAdapter.ExternalAssertionToken;
import spike.auth.SpikeSupport.MutableClock;

/** Question 1: can one abstraction host local credentials, federation and MFA behind Spring Security? */
class ProviderAbstractionTest {

    private final List<AuditEntry> audit = new ArrayList<>();
    private final MutableClock clock = new MutableClock();
    private LocalCredentialsProvider local;
    private FederatedStubProvider federated;
    private ProviderManager manager;

    @BeforeEach
    void setUp() {
        local = new LocalCredentialsProvider(SpikeSupport.delegatingEncoder(), clock);
        federated = new FederatedStubProvider();
        PlatformAuthenticationProvider stepUp = new PlatformAuthenticationProvider() {
            @Override
            public String id() {
                return "mfa-stub";
            }

            @Override
            public boolean supports(AuthenticationAttempt attempt) {
                return attempt instanceof AuthenticationAttempt.PasswordAttempt p
                        && p.identifier().startsWith("mfa-");
            }

            @Override
            public AuthenticationOutcome authenticate(AuthenticationAttempt attempt) {
                return new AuthenticationOutcome.ChallengeRequired("totp");
            }
        };
        // Order matters: the MFA stub claims identifiers starting with "mfa-" before local credentials.
        manager = new ProviderManager(new PlatformProviderAdapter(List.of(stepUp, local, federated), audit::add));
    }

    @Test
    void localCredentialsAuthenticate() {
        local.register("user-a@example.test", "correct horse battery staple");

        Authentication result = manager.authenticate(
                new UsernamePasswordAuthenticationToken("user-a@example.test", "correct horse battery staple"));

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getName()).isEqualTo(local.find("user-a@example.test").userId());
    }

    @Test
    void federatedProviderPlugsIntoTheSameAdapterWithoutChanges() {
        federated.link("https://idp.example.test", "subject-1", "user-x");

        Authentication result = manager.authenticate(
                new ExternalAssertionToken("https://idp.example.test", "subject-1"));

        assertThat(result.getName()).isEqualTo("user-x");
    }

    @Test
    void stepUpChallengeSurfacesAsItsOwnException() {
        assertThatThrownBy(() -> manager.authenticate(
                new UsernamePasswordAuthenticationToken("mfa-user-a@example.test", "pw")))
                .isInstanceOf(ChallengeRequiredException.class);
    }

    @Test
    void everyFailureLooksIdenticalToTheCallerButIsDistinguishableInTheAuditTrail() {
        local.register("user-a@example.test", "right-password-1");
        local.register("suspended@example.test", "right-password-2").suspend();
        local.register("locked@example.test", "right-password-3");
        for (int i = 0; i < LocalCredentialsProvider.LOCK_AFTER_FAILURES; i++) {
            attempt("locked@example.test", "wrong");
        }
        audit.clear();

        List<Throwable> failures = List.of(
                attempt("nobody@example.test", "whatever"),            // unknown account
                attempt("user-a@example.test", "wrong-password"),      // bad credentials
                attempt("suspended@example.test", "right-password-2"), // disabled
                attempt("locked@example.test", "right-password-3"),    // locked, even with the right password
                catchFailure(new ExternalAssertionToken("https://idp.example.test", "nobody"))); // not linked

        assertThat(failures).allSatisfy(failure -> {
            assertThat(failure).isInstanceOf(BadCredentialsException.class);
            assertThat(failure.getMessage()).isEqualTo(PlatformProviderAdapter.UNIFORM_MESSAGE);
        });
        assertThat(audit).extracting(AuditEntry::reason).containsExactly(
                Reason.UNKNOWN_ACCOUNT, Reason.BAD_CREDENTIALS, Reason.DISABLED, Reason.LOCKED,
                Reason.NO_LINKED_ACCOUNT);
    }

    private Throwable attempt(String identifier, String password) {
        return catchFailure(new UsernamePasswordAuthenticationToken(identifier, password));
    }

    private Throwable catchFailure(Authentication token) {
        try {
            manager.authenticate(token);
        } catch (RuntimeException e) {
            return e;
        }
        return null;
    }
}
