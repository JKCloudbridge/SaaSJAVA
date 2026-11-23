package app.platform.identity.internal;

import java.time.Duration;

/**
 * Slows the guessing of a client secret at the token endpoint (Sprint 31, ADR-0268). It counts <em>failed</em>
 * authentications only, in the same shared counters as sign-in (so every instance sees the same numbers, and an
 * instance counts for itself when Redis is down): per source address, and per client identifier from any source. It
 * is brute-force protection, not a quota (Sprint 32).
 *
 * <p>The per-client window is short on purpose: whoever fills it can keep the owner out for one window only. The
 * identifier is only ever used as a hash in a key, and the counter runs for an identifier that does not exist exactly
 * as for one that does, so a refusal reveals nothing about which applications exist.
 */
final class ClientAuthLimiter {

    private final Counters counters;
    private final ClientProperties.Failures limits;

    ClientAuthLimiter(Counters counters, ClientProperties.Failures limits) {
        this.counters = counters;
        this.limits = limits;
    }

    /** Whether an attempt may be evaluated now. Call before any secret is looked at. */
    boolean allowed(String source, String clientId) {
        return counters.current(sourceKey(source)) < limits.perSource()
                && counters.current(clientKey(clientId)) < limits.perClient();
    }

    /** Counts a failed authentication against its source and its client identifier. */
    void recordFailure(String source, String clientId) {
        counters.increment(sourceKey(source), limits.sourceWindow());
        counters.increment(clientKey(clientId), limits.clientWindow());
    }

    /** How long a refused caller should wait: the longest window that may apply. */
    Duration retryAfter() {
        return limits.sourceWindow().compareTo(limits.clientWindow()) >= 0 ? limits.sourceWindow()
                : limits.clientWindow();
    }

    private static String sourceKey(String source) {
        return "cfail:src:" + source;
    }

    private static String clientKey(String clientId) {
        return "cfail:cid:" + Hashes.sha256Hex(clientId == null ? "" : clientId.strip());
    }
}
