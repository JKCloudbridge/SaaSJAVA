package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.identity.AuthenticationAttempt;
import app.platform.identity.AuthenticationAttempt.PasswordAttempt;
import app.platform.identity.AuthenticationOutcome;
import app.platform.identity.PlatformAuthenticationProvider;
import app.platform.identity.RejectionReason;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.tenant.TenantContexts;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;

/**
 * Stories S3-SEC-05, S3-SEC-11 and S3-SEC-19: every failure looks identical to the caller, the true reason goes to the
 * audit trail, and a new kind of provider (a second factor, an external identity provider) needs no change in the
 * adapter.
 */
class PlatformProviderAdapterTest {

    private final List<AuditRecord> records = new ArrayList<>();
    private final AuthAudit audit = new AuthAudit(records::add, new TenantContexts());

    /** A provider whose answer the test chooses. */
    private static PlatformAuthenticationProvider provider(String id,
            Function<AuthenticationAttempt, AuthenticationOutcome> answer) {
        return new PlatformAuthenticationProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public boolean supports(AuthenticationAttempt attempt) {
                return attempt instanceof PasswordAttempt;
            }

            @Override
            public AuthenticationOutcome authenticate(AuthenticationAttempt attempt) {
                return answer.apply(attempt);
            }
        };
    }

    private static Authentication token(String identifier) {
        return new PlatformProviderAdapter.AttemptToken(
                new PasswordAttempt(identifier, "a-password-never-logged".toCharArray(), "203.0.113.5"), identifier);
    }

    @Test
    void everyKindOfRefusalReachesTheCallerAsTheSameBadCredentialsErrorWithTheSameText() {
        UUID user = UUID.randomUUID();
        for (RejectionReason reason : RejectionReason.values()) {
            if (reason == RejectionReason.UNAVAILABLE) {
                continue;
            }
            PlatformProviderAdapter adapter = new PlatformProviderAdapter(
                    List.of(provider("local", a -> new AuthenticationOutcome.Rejected(reason, user))), audit);

            assertThatThrownBy(() -> adapter.authenticate(token("user-a@example.test")))
                    .as(reason.name())
                    .isExactlyInstanceOf(BadCredentialsException.class)
                    .hasMessage(PlatformProviderAdapter.UNIFORM_MESSAGE);
        }
    }

    @Test
    void theTrueReasonOfEachFailureIsAuditedAndTheCallerNeverSeesIt() {
        for (RejectionReason reason : RejectionReason.values()) {
            if (reason == RejectionReason.UNAVAILABLE) {
                continue;
            }
            PlatformProviderAdapter adapter = new PlatformProviderAdapter(
                    List.of(provider("local", a -> new AuthenticationOutcome.Rejected(reason, null))), audit);
            try {
                adapter.authenticate(token("user-a@example.test"));
            } catch (BadCredentialsException expected) {
                // The point is what was recorded.
            }
        }

        assertThat(records).extracting(AuditRecord::reason)
                .containsExactly("unknown_account", "wrong_password", "locked", "disabled", "not_verified");
        assertThat(records).allSatisfy(record -> assertThat(record.type()).isEqualTo("auth.sign_in.failed"));
    }

    @Test
    void noRecordHoldsThePasswordAndAnUnknownIdentifierIsOnlyAHash() {
        PlatformProviderAdapter adapter = new PlatformProviderAdapter(
                List.of(provider("local", a -> new AuthenticationOutcome.Rejected(RejectionReason.UNKNOWN_ACCOUNT,
                        null))), audit);
        try {
            adapter.authenticate(token("typed-password-by-mistake-in-address-field"));
        } catch (BadCredentialsException expected) {
            // Recorded below.
        }

        assertThat(records.toString()).doesNotContain("a-password-never-logged")
                .doesNotContain("typed-password-by-mistake");
        assertThat(records.get(0).attributes()).containsKey("identifier_hash");
    }

    @Test
    void aSuccessIsAuditedAndYieldsAnAuthenticationNamedByTheUserId() {
        UUID user = UUID.randomUUID();
        PlatformProviderAdapter adapter = new PlatformProviderAdapter(
                List.of(provider("local", a -> new AuthenticationOutcome.Authenticated(user))), audit);

        Authentication result = adapter.authenticate(token("user-a@example.test"));

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getName()).isEqualTo(user.toString());
        assertThat(records).extracting(AuditRecord::type).containsExactly("auth.sign_in.succeeded");
    }

    @Test
    void aChallengeIsItsOwnExceptionTypeButStillReadsTheSameToTheCaller() {
        PlatformProviderAdapter adapter = new PlatformProviderAdapter(
                List.of(provider("totp", a -> new AuthenticationOutcome.ChallengeRequired("totp", UUID.randomUUID()))),
                audit);

        assertThatThrownBy(() -> adapter.authenticate(token("user-a@example.test")))
                .isExactlyInstanceOf(PlatformProviderAdapter.ChallengeRequiredException.class)
                .hasMessage(PlatformProviderAdapter.UNIFORM_MESSAGE);
        assertThat(records.get(0).reason()).isEqualTo("challenge_required");
    }

    @Test
    void aProviderThatCouldNotDecideIsNotAVerdictOnThePerson() {
        PlatformProviderAdapter adapter = new PlatformProviderAdapter(
                List.of(provider("local", a -> new AuthenticationOutcome.Rejected(RejectionReason.UNAVAILABLE,
                        null))), audit);

        assertThatThrownBy(() -> adapter.authenticate(token("user-a@example.test")))
                .isInstanceOf(AuthenticationServiceException.class);
    }

    @Test
    void anotherProviderPlugsInWithoutAnyChangeToTheAdapter() {
        // A stand-in for an external identity provider: the adapter code is the same, only the list differs.
        UUID federated = UUID.randomUUID();
        PlatformAuthenticationProvider external = provider("external-idp",
                a -> new AuthenticationOutcome.Authenticated(federated));
        PlatformProviderAdapter adapter = new PlatformProviderAdapter(List.of(external), audit);

        assertThat(adapter.authenticate(token("anyone")).getName()).isEqualTo(federated.toString());
        assertThat(records.get(0).attributes()).containsEntry("provider", "external-idp");
    }

    @Test
    void noProviderForTheAttemptStillAnswersTheSameUniformFailure() {
        PlatformProviderAdapter adapter = new PlatformProviderAdapter(List.of(), audit);

        assertThatThrownBy(() -> adapter.authenticate(token("user-a@example.test")))
                .isExactlyInstanceOf(BadCredentialsException.class).hasMessage(PlatformProviderAdapter.UNIFORM_MESSAGE);
    }

    @Test
    void theTextFormOfAnAttemptNeverShowsThePassword() {
        PasswordAttempt attempt = new PasswordAttempt("user-a@example.test", "a-password-never-logged".toCharArray(),
                "203.0.113.5");

        assertThat(attempt.toString()).doesNotContain("a-password-never-logged").doesNotContain("user-a");
    }
}
