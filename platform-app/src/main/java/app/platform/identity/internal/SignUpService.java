package app.platform.identity.internal;

import app.platform.identity.AccountTokenPurpose;
import app.platform.identity.Users;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.mail.MailQueue;
import app.platform.sharedkernel.mail.MailRequest;
import app.platform.sharedkernel.mail.MailTemplate;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.time.Clock;
import java.util.Arrays;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sign-up in two steps (ADR-0023). The person gives only an address; an e-mail with a link follows if the address may
 * sign up; opening the link and choosing a name and a password creates the account. Nothing exists, and nothing is
 * reserved, before the link is used, so nobody can take over an address by signing up with it, and the request leaks
 * nothing about accounts.
 *
 * <p><strong>The request does identical work for every address</strong> (story S3-SEC-07): a format check, the limits,
 * one queue row and one audit row, with no look at the user table. What is actually sent (a link, a notice that an
 * account exists, or nothing) is decided later by the mail relay, where nobody waits for it.
 */
@Service
class SignUpService {

    private static final String INVALID_LINK = "This link is not valid or has expired.";

    private final AccountLimiter limiter;
    private final MailQueue mail;
    private final AuthAudit audit;
    private final AccountTokenRepository tokens;
    private final Users users;
    private final TransactionTemplate transaction;
    private final Clock clock;

    SignUpService(AccountLimiter limiter, MailQueue mail, AuthAudit audit, AccountTokenRepository tokens,
            Users users, TransactionTemplate transaction, Clock clock) {
        this.limiter = limiter;
        this.mail = mail;
        this.audit = audit;
        this.tokens = tokens;
        this.users = users;
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * Starts a sign-up. The caller answers the same way whatever this does.
     *
     * @throws ApiException {@code VALIDATION_ERROR} for text that cannot be an address (the same for every address),
     *         {@code RATE_LIMITED}
     */
    void request(String emailText, String source) {
        String email = Emails.normalize(emailText)
                .orElseThrow(() -> ApiException.validation("email", "Is not a valid address."));
        limiter.admitRequest(AccountLimiter.Kind.SIGN_UP, source, email);
        transaction.executeWithoutResult(status -> {
            mail.enqueue(MailRequest.of(MailTemplate.SIGN_UP_REQUEST, email));
            audit.signUpRequested(email, source);
        });
    }

    /**
     * Completes a sign-up: the token proves the address, the person chooses a name and a password, and the account
     * is created and active. One transaction: a password that breaks the policy leaves the token usable.
     *
     * @throws ApiException {@code VALIDATION_ERROR} on the field {@code token} for any link that is not usable, or on
     *         {@code password} or {@code displayName}; {@code RATE_LIMITED}
     */
    void complete(String token, String displayName, char[] password, String source) {
        try {
            limiter.admitTokenAttempt(source);
            AccountTokenRepository.Live live = tokens
                    .findLive(Hashes.hashed(token), AccountTokenPurpose.SIGN_UP, clock.instant())
                    .orElseThrow(() -> refused("not_live", source));
            try {
                transaction.executeWithoutResult(status -> {
                    if (!tokens.consume(live.id(), clock.instant())) {
                        throw new LinkRefusal("already_used");
                    }
                    try {
                        var user = users.createActive(live.email(), displayName, password, ActorId.SYSTEM);
                        audit.signUpCompleted(user.id());
                    } catch (ApiException e) {
                        if (e.code() == ErrorCode.CONFLICT) {
                            // The address got an account by another way since the link was sent.
                            throw new LinkRefusal("address_has_account");
                        }
                        throw e;
                    }
                });
            } catch (LinkRefusal e) {
                // Recorded after the transaction ended: a record written inside it would roll back with it.
                throw refused(e.reason(), source);
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private ApiException refused(String reason, String source) {
        audit.linkRefused("SIGN_UP", reason, source);
        return ApiException.validation("token", INVALID_LINK);
    }
}
