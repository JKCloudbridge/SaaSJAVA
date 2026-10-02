package app.platform.identity.internal;

import app.platform.identity.AccountTokenPurpose;
import app.platform.identity.User;
import app.platform.identity.UserStatus;
import app.platform.identity.Users;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.mail.MailQueue;
import app.platform.sharedkernel.mail.MailRequest;
import app.platform.sharedkernel.mail.MailTemplate;
import app.platformapi.ApiException;
import java.time.Clock;
import java.util.Arrays;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Password reset in two steps (ADR-0023). A request names an address; an e-mail with a link follows only if the address
 * has an active account; the link and a new password complete it.
 *
 * <p>Like sign-up, <strong>the request does identical work for every address</strong> and answers the same; the relay
 * decides later what to send. Completing a reset <strong>is never blocked by a lock</strong> (ADR-0021: the reset is
 * the owner's way back in when someone keeps their account locked); it clears the lock, ends every session of the user,
 * cancels every other reset link, and tells the owner by e-mail.
 */
@Service
class PasswordResetService {

    private static final String INVALID_LINK = "This link is not valid or has expired.";

    private final AccountLimiter limiter;
    private final MailQueue mail;
    private final AuthAudit audit;
    private final AccountTokenRepository tokens;
    private final Users users;
    private final TransactionTemplate transaction;
    private final Clock clock;

    PasswordResetService(AccountLimiter limiter, MailQueue mail, AuthAudit audit, AccountTokenRepository tokens,
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
     * Starts a reset. The caller answers the same way whatever this does.
     *
     * @throws ApiException {@code VALIDATION_ERROR} for text that cannot be an address, {@code RATE_LIMITED}
     */
    void request(String emailText, String source) {
        String email = Emails.normalize(emailText)
                .orElseThrow(() -> ApiException.validation("email", "Is not a valid address."));
        limiter.admitRequest(AccountLimiter.Kind.PASSWORD_RESET, source, email);
        transaction.executeWithoutResult(status -> {
            mail.enqueue(MailRequest.of(MailTemplate.PASSWORD_RESET_REQUEST, email));
            audit.passwordResetRequested(email, source);
        });
    }

    /**
     * Sets the new password. One transaction: a password that breaks the policy leaves the link usable.
     *
     * @throws ApiException {@code VALIDATION_ERROR} on the field {@code token} for any link that is not usable, or on
     *         {@code newPassword} for the policy; {@code RATE_LIMITED}
     */
    void complete(String token, char[] password, String source) {
        try {
            limiter.admitTokenAttempt(source);
            AccountTokenRepository.Live live = tokens
                    .findLive(Hashes.hashed(token), AccountTokenPurpose.PASSWORD_RESET, clock.instant())
                    .orElseThrow(() -> refused("not_live", source));
            try {
                transaction.executeWithoutResult(status -> {
                    if (!tokens.consume(live.id(), clock.instant())) {
                        throw new LinkRefusal("already_used");
                    }
                    User user = users.findById(live.userId()).orElseThrow(() -> new LinkRefusal("no_account"));
                    if (user.status() != UserStatus.ACTIVE) {
                        throw new LinkRefusal("account_not_active");
                    }
                    // No look at the lock: a reset always works, and storing the password clears the lock.
                    users.resetPassword(user.id(), password, new ActorId(user.id()));
                    mail.enqueue(MailRequest.of(MailTemplate.PASSWORD_CHANGED, user.email()).forUser(user.id()));
                    audit.passwordResetCompleted(user.id());
                });
            } catch (LinkRefusal e) {
                throw refused(e.reason(), source);
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private ApiException refused(String reason, String source) {
        audit.linkRefused("PASSWORD_RESET", reason, source);
        return ApiException.validation("token", INVALID_LINK);
    }
}
