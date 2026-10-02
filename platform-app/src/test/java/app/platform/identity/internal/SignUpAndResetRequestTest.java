package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import app.platform.identity.Users;
import app.platform.sharedkernel.mail.MailQueue;
import app.platform.sharedkernel.mail.MailRequest;
import app.platform.sharedkernel.mail.MailTemplate;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The request steps of sign-up and reset do the same work for every address (story S3-SEC-07, ADR-0023): they never
 * look at the user table or the token table. The test is the guard: a lookup added to the request path fails it
 * (checked by adding one).
 */
class SignUpAndResetRequestTest {

    private final AccountLimiter limiter = mock(AccountLimiter.class);
    private final MailQueue mail = mock(MailQueue.class);
    private final AuthAudit audit = mock(AuthAudit.class);
    private final AccountTokenRepository tokens = mock(AccountTokenRepository.class);
    private final Users users = mock(Users.class);
    private final TransactionTemplate transaction = new TransactionTemplate(mock(PlatformTransactionManager.class));

    private final SignUpService signUp = new SignUpService(limiter, mail, audit, tokens, users, transaction,
            Clock.systemUTC());
    private final PasswordResetService reset = new PasswordResetService(limiter, mail, audit, tokens, users,
            transaction, Clock.systemUTC());

    @Test
    void aSignUpRequestOnlyCountsQueuesAndAudits() {
        signUp.request("  Person-A@Example.TEST ", "203.0.113.9");

        verify(limiter).admitRequest(AccountLimiter.Kind.SIGN_UP, "203.0.113.9", "person-a@example.test");
        ArgumentCaptor<MailRequest> queued = ArgumentCaptor.forClass(MailRequest.class);
        verify(mail).enqueue(queued.capture());
        assertThat(queued.getValue().template()).isEqualTo(MailTemplate.SIGN_UP_REQUEST);
        assertThat(queued.getValue().email()).isEqualTo("person-a@example.test");
        assertThat(queued.getValue().userId()).as("no user is looked up or named").isNull();
        verify(audit).signUpRequested("person-a@example.test", "203.0.113.9");
        verifyNoInteractions(users, tokens);
    }

    @Test
    void aResetRequestOnlyCountsQueuesAndAudits() {
        reset.request("Person-A@Example.test", "203.0.113.9");

        verify(limiter).admitRequest(AccountLimiter.Kind.PASSWORD_RESET, "203.0.113.9", "person-a@example.test");
        ArgumentCaptor<MailRequest> queued = ArgumentCaptor.forClass(MailRequest.class);
        verify(mail).enqueue(queued.capture());
        assertThat(queued.getValue().template()).isEqualTo(MailTemplate.PASSWORD_RESET_REQUEST);
        verify(audit).passwordResetRequested("person-a@example.test", "203.0.113.9");
        verifyNoInteractions(users, tokens);
    }

    @Test
    void textThatCannotBeAnAddressIsRefusedBeforeAnythingIsCounted() {
        assertThatThrownBy(() -> signUp.request("not an address", "203.0.113.9"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verifyNoInteractions(limiter, mail, users, tokens);
    }

    @Test
    void aRefusedRequestQueuesNothing() {
        org.mockito.Mockito.doThrow(ApiException.rateLimited(60)).when(limiter)
                .admitRequest(any(), eq("203.0.113.9"), any());

        assertThatThrownBy(() -> reset.request("person-a@example.test", "203.0.113.9"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED));

        verifyNoInteractions(mail, audit);
    }
}
