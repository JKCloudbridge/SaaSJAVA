package app.platform.identity.internal;

import java.time.Duration;

/**
 * Rate limits for sign-in (story S3-SEC-09, ADR-0021), counted in {@link Counters} so that every instance sees the same
 * numbers.
 *
 * <ul>
 *   <li>Per source: attempts of any outcome in a short window (bounds the hashing work one source can cause) and
 *       failed attempts in a longer window (stops one source from guessing or from spraying one password over many
 *       accounts).</li>
 *   <li>Per identifier: attempts from any source in a short window. It is deliberately short: whoever fills it can keep
 *       the owner out for one window only, never longer, and the owner's own correct attempt after the window works.
 *       The counter runs for unknown identifiers exactly as for real ones, so a refusal reveals nothing about an
 *       account.</li>
 * </ul>
 * The lock of an account (database) is a separate mechanism.
 */
final class SignInLimiter {

    /** The answer to "may this attempt be evaluated?". */
    enum Verdict {

        /** Go ahead. */
        ALLOWED,

        /** Too many attempts from this source. */
        SOURCE_ATTEMPTS,

        /** Too many failed attempts from this source. */
        SOURCE_FAILURES,

        /** Too many attempts for this identifier. */
        IDENTIFIER_ATTEMPTS;

        boolean allowed() {
            return this == ALLOWED;
        }
    }

    private final Counters counters;
    private final IdentityProperties.RateLimit limits;

    SignInLimiter(Counters counters, IdentityProperties.RateLimit limits) {
        this.counters = counters;
        this.limits = limits;
    }

    /**
     * Counts an attempt and says whether it may be evaluated. Call before any password work.
     *
     * @param source the normalized source (see {@link ClientSource})
     * @param identifier the identifier as typed; only a hash of it is used as a key
     */
    Verdict admit(String source, String identifier) {
        if (counters.increment("att:src:" + source, limits.sourceAttemptWindow()) > limits.sourceAttempts()) {
            return Verdict.SOURCE_ATTEMPTS;
        }
        String identifierKey = Hashes.sha256Hex(identifier.strip().toLowerCase(java.util.Locale.ROOT));
        if (counters.increment("att:id:" + identifierKey, limits.identifierWindow()) > limits.identifierAttempts()) {
            return Verdict.IDENTIFIER_ATTEMPTS;
        }
        if (counters.current("fail:src:" + source) >= limits.sourceFailures()) {
            return Verdict.SOURCE_FAILURES;
        }
        return Verdict.ALLOWED;
    }

    /** Counts a failed attempt against its source. */
    void recordFailure(String source) {
        counters.increment("fail:src:" + source, limits.sourceFailureWindow());
    }

    /** How long a refused caller should wait before trying again (the longest window that applies, as a bound). */
    Duration retryAfter(Verdict verdict) {
        return switch (verdict) {
            case ALLOWED -> Duration.ZERO;
            case SOURCE_ATTEMPTS -> limits.sourceAttemptWindow();
            case SOURCE_FAILURES -> limits.sourceFailureWindow();
            case IDENTIFIER_ATTEMPTS -> limits.identifierWindow();
        };
    }
}
