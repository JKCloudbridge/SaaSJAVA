package app.platform.identity.internal;

import app.platform.identity.AccountTokenPurpose;
import app.platform.identity.AccountTokens;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates link tokens (ADR-0023): 32 random bytes, stored only as a SHA-256 hash, valid for the configured life, and a
 * new one cancels the older unused ones of the same purpose for the same address, in one transaction.
 */
@Service
class DefaultAccountTokens implements AccountTokens {

    private final AccountTokenRepository tokens;
    private final IdentityProperties.Account settings;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final AuthAudit audit;

    DefaultAccountTokens(AccountTokenRepository tokens, IdentityProperties properties,
            TransactionTemplate transaction, Clock clock, AuthAudit audit) {
        this.audit = audit;
        this.tokens = tokens;
        this.settings = properties.account();
        this.transaction = transaction;
        this.clock = clock;
    }

    @Override
    public String issue(AccountTokenPurpose purpose, String email, UUID userId) {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(email, "email");
        if (purpose == AccountTokenPurpose.PASSWORD_RESET && userId == null) {
            throw new IllegalArgumentException("A reset token belongs to an account");
        }
        String token = Hashes.randomSecret();
        Duration life = lifetime(purpose);
        transaction.executeWithoutResult(status -> {
            tokens.cancelOpen(purpose, email);
            tokens.insert(purpose, email, userId, Hashes.hashed(token), clock.instant().plus(life));
            audit.tokenCreated(purpose.name(), userId);
        });
        return token;
    }

    @Override
    public Duration lifetime(AccountTokenPurpose purpose) {
        return purpose == AccountTokenPurpose.SIGN_UP ? settings.signUpLinkLife() : settings.resetLinkLife();
    }
}
