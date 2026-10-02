package app.platform.identity.internal;

import app.platform.identity.AuthenticationAttempt;
import app.platform.identity.AuthenticationAttempt.PasswordAttempt;
import app.platform.identity.AuthenticationOutcome;
import app.platform.identity.PlatformAuthenticationProvider;
import app.platform.identity.RejectionReason;
import app.platform.identity.User;
import app.platform.identity.UserStatus;
import app.platform.sharedkernel.mail.MailQueue;
import app.platform.sharedkernel.mail.MailRequest;
import app.platform.sharedkernel.mail.MailTemplate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves identity with an address and a password held by the platform (decision D2, ADR-0005).
 *
 * <p><strong>Same work on every path</strong> (story S3-SEC-06): whatever the account's state, exactly one password
 * verification runs before the answer is decided: against the real hash when the account has a credential, against a
 * hash nobody can match when it does not. So "unknown", "locked", "disabled" and "wrong password" cost the same time
 * and cannot be told apart by measuring. The real reason is decided afterwards and returned only inside the outcome,
 * for the audit trail.
 *
 * <p>Failures are counted in the database under a row lock ({@link LockoutPolicy}); an attempt made while the account
 * is locked is refused without being counted. A successful check clears the count, and upgrades the stored hash when
 * it is weaker than the current settings.
 */
@Component
class LocalCredentialsProvider implements PlatformAuthenticationProvider {

    static final String ID = "local";

    private final UserRepository users;
    private final CredentialRepository credentials;
    private final PasswordHasher hasher;
    private final PasswordPolicy policy;
    private final LockoutPolicy lockout;
    private final AuthAudit audit;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final MailQueue mail;
    private final Duration lockMailInterval;

    LocalCredentialsProvider(UserRepository users, CredentialRepository credentials, PasswordHasher hasher,
            PasswordPolicy policy, LockoutPolicy lockout, AuthAudit audit, TransactionTemplate transaction,
            Clock clock, MailQueue mail, IdentityProperties properties) {
        this.mail = mail;
        this.lockMailInterval = properties.account().lockMailInterval();
        this.users = users;
        this.credentials = credentials;
        this.hasher = hasher;
        this.policy = policy;
        this.lockout = lockout;
        this.audit = audit;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean supports(AuthenticationAttempt attempt) {
        return attempt instanceof PasswordAttempt;
    }

    @Override
    public AuthenticationOutcome authenticate(AuthenticationAttempt attempt) {
        PasswordAttempt password = (PasswordAttempt) attempt;
        Optional<User> user = Emails.normalize(password.identifier()).flatMap(users::findByEmail);
        Optional<CredentialRepository.Credential> credential = user.flatMap(found -> credentials.find(found.id()));

        boolean passwordCorrect;
        try {
            passwordCorrect = verify(password.password(), credential);
        } catch (PasswordHasher.HashingBusyException e) {
            return new AuthenticationOutcome.Rejected(RejectionReason.UNAVAILABLE, user.map(User::id).orElse(null));
        }

        if (user.isEmpty()) {
            return new AuthenticationOutcome.Rejected(RejectionReason.UNKNOWN_ACCOUNT, null);
        }
        User found = user.get();
        if (credential.isEmpty() || found.status() == UserStatus.INVITED) {
            return new AuthenticationOutcome.Rejected(RejectionReason.NOT_VERIFIED, found.id());
        }
        if (!found.status().canSignIn()) {
            return new AuthenticationOutcome.Rejected(RejectionReason.DISABLED, found.id());
        }
        Instant now = clock.instant();
        if (credential.get().state().isLocked(now)) {
            return new AuthenticationOutcome.Rejected(RejectionReason.LOCKED, found.id());
        }
        if (!passwordCorrect) {
            registerFailure(found, now, password.source());
            return new AuthenticationOutcome.Rejected(RejectionReason.WRONG_PASSWORD, found.id());
        }
        succeed(found, credential.get(), password.password());
        return new AuthenticationOutcome.Authenticated(found.id());
    }

    /** Exactly one verification, whatever the state of the account. */
    private boolean verify(char[] typed, Optional<CredentialRepository.Credential> credential) {
        char[] bounded = typed.length > policy.maxLength() ? Arrays.copyOf(typed, policy.maxLength()) : typed;
        try {
            if (credential.isEmpty() || typed.length > policy.maxLength()) {
                // No account, or a password longer than any valid one: the same work, never a match.
                hasher.matchesNothing(bounded);
                return false;
            }
            return hasher.matches(bounded, credential.get().passwordHash());
        } finally {
            if (bounded != typed) {
                Arrays.fill(bounded, '\0');
            }
        }
    }

    private void registerFailure(User user, Instant now, String source) {
        LockoutPolicy.Failure failure = transaction.execute(status -> {
            // Lock the row so two simultaneous failures are counted one after the other.
            CredentialRepository.Credential current = credentials.findForUpdate(user.id()).orElse(null);
            if (current == null) {
                return null;
            }
            LockoutPolicy.Failure result = lockout.afterFailure(current.state(), now);
            if (!result.lockedNow()) {
                credentials.storeState(user.id(), result.state());
            }
            if (result.lockLength() != null) {
                notifyOwner(user);
            }
            return result;
        });
        if (failure != null && failure.lockLength() != null) {
            audit.accountLocked(user.id(), failure.lockLength().toSeconds(), source);
        }
    }

    /**
     * Tells the owner their account was locked (ADR-0021, ADR-0023), at most once per interval: the row is locked here,
     * so two instances cannot both pass the check, and a persistent attacker cannot turn the notice into spam.
     */
    private void notifyOwner(User user) {
        if (!mail.queuedRecently(MailTemplate.ACCOUNT_LOCKED, user.id(), lockMailInterval.toSeconds())) {
            mail.enqueue(MailRequest.of(MailTemplate.ACCOUNT_LOCKED, user.email()).forUser(user.id()));
        }
    }

    private void succeed(User user, CredentialRepository.Credential credential, char[] typed) {
        credentials.recordSuccess(user.id());
        if (hasher.needsUpgrade(credential.passwordHash())) {
            try {
                credentials.upgradeHash(user.id(), hasher.hash(typed));
                audit.passwordHashUpgraded(user.id());
            } catch (PasswordHasher.HashingBusyException e) {
                // The sign-in already succeeded; the upgrade happens at the next one.
                return;
            }
        }
    }
}
