package app.platform.identity.internal;

import app.platform.identity.User;
import app.platform.identity.UserStatus;
import app.platform.identity.Users;
import app.platform.sharedkernel.ActorId;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Users and their credentials. Every change and its audit record are one transaction, and whatever takes a user out of
 * service or changes their password also ends their sessions and tokens in the same transaction (ADR-0019, ADR-0020).
 */
@Service
class DefaultUsers implements Users {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultUsers.class);
    private static final int MAX_DISPLAY_NAME = 200;
    private static final String CHANGE_LIMIT_KEY = "pwchg:";

    private final UserRepository users;
    private final CredentialRepository credentials;
    private final PasswordHasher hasher;
    private final PasswordPolicy policy;
    private final SessionRevocation revocation;
    private final AuthAudit audit;
    private final TransactionTemplate transaction;
    private final Counters counters;
    private final IdentityProperties.RateLimit limits;
    private final AccountTokenRepository accountTokens;

    DefaultUsers(UserRepository users, CredentialRepository credentials, PasswordHasher hasher,
            PasswordPolicy policy, SessionRevocation revocation, AuthAudit audit, TransactionTemplate transaction,
            Counters counters, IdentityProperties properties, AccountTokenRepository accountTokens) {
        this.accountTokens = accountTokens;
        this.users = users;
        this.credentials = credentials;
        this.hasher = hasher;
        this.policy = policy;
        this.revocation = revocation;
        this.audit = audit;
        this.transaction = transaction;
        this.counters = counters;
        this.limits = properties.rateLimit();
    }

    @Override
    public User createInvited(String email, String displayName, ActorId actor) {
        String address = validEmail(email);
        String name = validDisplayName(displayName);
        return inTransaction(() -> {
            UUID id = insertUser(address, name, actor);
            audit.userCreated(id, actor.value());
            return found(id);
        });
    }

    @Override
    public User createActive(String email, String displayName, char[] password, ActorId actor) {
        String address = validEmail(email);
        String name = validDisplayName(displayName);
        requireAcceptable(password, address, "password");
        String hash = hash(password);
        return inTransaction(() -> {
            UUID id = insertUser(address, name, actor);
            credentials.insert(id, hash, actor);
            User invited = found(id);
            moveTo(invited, UserStatus.ACTIVE, actor);
            audit.userCreated(id, actor.value());
            return found(id);
        });
    }

    @Override
    public User activate(UUID userId, char[] password, ActorId actor) {
        User current = users.findById(userId).orElseThrow(() -> ApiException.notFound("The user was not found."));
        requireAcceptable(password, current.email(), "password");
        String hash = hash(password);
        return inTransaction(() -> {
            User locked = users.findForUpdate(userId)
                    .orElseThrow(() -> ApiException.notFound("The user was not found."));
            if (locked.status() != UserStatus.INVITED) {
                throw new ApiException(ErrorCode.CONFLICT, "The user cannot be activated.");
            }
            if (credentials.find(userId).isPresent()) {
                credentials.replacePassword(userId, hash, actor);
            } else {
                credentials.insert(userId, hash, actor);
            }
            moveTo(locked, UserStatus.ACTIVE, actor);
            return found(userId);
        });
    }

    @Override
    public Optional<User> findById(UUID id) {
        return users.findById(id);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return Emails.normalize(email).flatMap(users::findByEmail);
    }

    @Override
    public User suspend(UUID userId, ActorId actor) {
        return transition(userId, EnumSet.of(UserStatus.ACTIVE), UserStatus.SUSPENDED, actor, "user_suspended");
    }

    @Override
    public User reinstate(UUID userId, ActorId actor) {
        return transition(userId, EnumSet.of(UserStatus.SUSPENDED), UserStatus.ACTIVE, actor, null);
    }

    @Override
    public User deactivate(UUID userId, ActorId actor) {
        return transition(userId, EnumSet.allOf(UserStatus.class), UserStatus.DEACTIVATED, actor,
                "user_deactivated");
    }

    @Override
    public void changePassword(UUID userId, char[] currentPassword, char[] newPassword) {
        if (counters.increment(CHANGE_LIMIT_KEY + userId, limits.sourceFailureWindow()) > limits.identifierAttempts()) {
            throw ApiException.rateLimited(limits.sourceFailureWindow().toSeconds());
        }
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("The user was not found."));
        CredentialRepository.Credential credential = credentials.find(userId)
                .orElseThrow(() -> ApiException.notFound("The user was not found."));
        boolean currentIsRight;
        try {
            currentIsRight = hasher.matches(currentPassword, credential.passwordHash());
        } catch (PasswordHasher.HashingBusyException e) {
            throw ApiException.unavailable(1);
        }
        if (!currentIsRight) {
            audit.passwordChangeRefused(userId, "wrong_current_password");
            throw ApiException.validation("currentPassword", "Is not correct.");
        }
        requireAcceptable(newPassword, user.email(), "newPassword");
        if (hasher.matches(newPassword, credential.passwordHash())) {
            audit.passwordChangeRefused(userId, "same_as_current");
            throw ApiException.validation("newPassword", "Must differ from the current password.");
        }
        storeNewPassword(user, hash(newPassword), new ActorId(userId), "password_changed", "self");
    }

    @Override
    public void resetPassword(UUID userId, char[] newPassword, ActorId actor) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("The user was not found."));
        requireAcceptable(newPassword, user.email(), "newPassword");
        storeNewPassword(user, hash(newPassword), actor, "password_reset", "reset");
    }

    @Override
    public void signOutEverywhere(UUID userId, ActorId actor) {
        inTransaction(() -> {
            users.findForUpdate(userId).orElseThrow(() -> ApiException.notFound("The user was not found."));
            users.bumpSecurityVersion(userId, actor);
            revocation.revokeAll(userId, "sign_out_everywhere", actor);
            audit.signedOutEverywhere(userId, actor.value());
            return null;
        });
    }

    // ---- internals ----

    private void storeNewPassword(User user, String hash, ActorId actor, String reason, String how) {
        inTransaction(() -> {
            users.findForUpdate(user.id()).orElseThrow(() -> ApiException.notFound("The user was not found."));
            if (credentials.find(user.id()).isPresent()) {
                credentials.replacePassword(user.id(), hash, actor);
            } else {
                credentials.insert(user.id(), hash, actor);
            }
            users.bumpSecurityVersion(user.id(), actor);
            revocation.revokeAll(user.id(), reason, actor);
            // Links sent before the password changed must not work any more.
            accountTokens.cancelOpenResetsOf(user.id());
            audit.passwordChanged(user.id(), how);
            return null;
        });
    }

    private User transition(UUID userId, Set<UserStatus> from, UserStatus target, ActorId actor,
            String revokeReason) {
        User changed = inTransaction(() -> {
            User current = users.findForUpdate(userId)
                    .orElseThrow(() -> ApiException.notFound("The user was not found."));
            if (!from.contains(current.status()) || !current.status().canTransitionTo(target)) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "The user cannot change from " + current.status() + " to " + target + ".");
            }
            moveTo(current, target, actor);
            if (revokeReason != null) {
                revocation.revokeAll(userId, revokeReason, actor);
            }
            return found(userId);
        });
        LOG.info("User status changed to {}", target);
        return changed;
    }

    private void moveTo(User current, UserStatus target, ActorId actor) {
        if (!users.updateStatus(current, target, actor)) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        audit.userStatusChanged(current.id(), current.status().name(), target.name(), actor.value());
    }

    private UUID insertUser(String address, String name, ActorId actor) {
        try {
            return users.insert(address, name, actor);
        } catch (DuplicateKeyException e) {
            // The unique index on the address decided it; no driver text is kept.
            throw new ApiException(ErrorCode.CONFLICT, "An account with this email address already exists.");
        }
    }

    private User found(UUID id) {
        return users.findById(id).orElseThrow(() -> new IllegalStateException("User vanished"));
    }

    private String hash(char[] password) {
        try {
            return hasher.hash(password);
        } catch (PasswordHasher.HashingBusyException e) {
            throw ApiException.unavailable(1);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private void requireAcceptable(char[] password, String email, String field) {
        List<String> problems = policy.violations(password, email);
        if (!problems.isEmpty()) {
            Arrays.fill(password, '\0');
            throw ApiException.validation(Map.of(field, problems));
        }
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    private static String validEmail(String email) {
        return Emails.normalize(email).orElseThrow(() -> ApiException.validation("email", "Is not a valid address."));
    }

    private static String validDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw ApiException.validation("displayName", "Is required.");
        }
        if (!displayName.equals(displayName.strip()) || displayName.length() > MAX_DISPLAY_NAME) {
            throw ApiException.validation("displayName",
                    "Must have at most " + MAX_DISPLAY_NAME + " characters and no leading or trailing spaces.");
        }
        return displayName;
    }
}
