package app.platform.identity.internal;

import app.platformapi.ApiException;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * Rate limits of sign-up, password reset and completing them (ADR-0023), counted in {@link Counters} so that every
 * instance sees the same numbers.
 *
 * <p>A request is counted before anything about the address is looked at, and whether the address is known or not
 * makes no difference to any number, so a refusal (429) reveals nothing about an account. Two limits protect a
 * mailbox and the service: one per source address and kind (so one caller cannot start many sign-ups or resets), and
 * one per address for all kinds together, from any source (so nobody's inbox can be flooded through this API). The
 * second has a price that the ADR states: a persistent caller can use up the allowance of a victim's address and keep
 * their reset mail from being queued for the rest of the window.
 */
final class AccountLimiter {

    /** The kinds of request that start a mail. */
    enum Kind {
        SIGN_UP, PASSWORD_RESET
    }

    private final Counters counters;
    private final IdentityProperties.Account limits;

    AccountLimiter(Counters counters, IdentityProperties.Account limits) {
        this.counters = counters;
        this.limits = limits;
    }

    /**
     * Counts a request that would start a mail and refuses it when a limit is exceeded.
     *
     * @param source the normalized source (see {@link ClientSource})
     * @param email the normalized address; only its hash is used as a key
     * @throws ApiException {@code RATE_LIMITED} with the wait in seconds
     */
    void admitRequest(Kind kind, String source, String email) {
        int perSource = kind == Kind.SIGN_UP ? limits.signUpsPerSource() : limits.resetsPerSource();
        if (counters.increment("acct:" + kind + ":src:" + source, limits.requestWindow()) > perSource) {
            throw ApiException.rateLimited(limits.requestWindow().toSeconds());
        }
        String key = Hashes.sha256Hex(email.toLowerCase(Locale.ROOT));
        if (counters.increment("acct:addr:" + key, limits.addressWindow()) > limits.mailsPerAddress()) {
            throw ApiException.rateLimited(limits.addressWindow().toSeconds());
        }
    }

    /** Counts an attempt to complete a sign-up or a reset and refuses it when the source made too many. */
    void admitTokenAttempt(String source) {
        if (counters.increment("acct:tok:src:" + source, limits.tokenWindow()) > limits.tokenAttempts()) {
            throw ApiException.rateLimited(limits.tokenWindow().toSeconds());
        }
    }

    /** Counts a founding of an organization by one person and refuses it when they founded too many per hour. */
    void admitFounding(UUID userId) {
        Duration window = Duration.ofHours(1);
        if (counters.increment("acct:found:" + userId, window) > limits.foundingsPerHour()) {
            throw ApiException.rateLimited(window.toSeconds());
        }
    }
}
