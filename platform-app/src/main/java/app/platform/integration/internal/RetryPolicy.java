package app.platform.integration.internal;

import java.time.Duration;

/**
 * Which failures of a send are tried again, which never are, and how long to wait (Sprint 29, ADR-0227). Pure: no
 * database, no clock, no randomness of its own (the caller hands the random number in), so every bound is tested as a
 * property.
 *
 * <p><strong>Classified from the kind of failure, never from a message.</strong> What the other side said is not read
 * for this: only the stable reason of the network layer or the status number. A failure nobody listed is permanent,
 * because trying again what is not understood only repeats it and hides it.
 *
 * <p>Each verdict also says what the failure shows about the other side ({@link Health}), which is what the circuit
 * breaker counts: a refusal to authenticate or a rejected record shows the service is alive and answering.
 */
final class RetryPolicy {

    /** Whether a later attempt could succeed. */
    enum Kind {
        RETRY,
        PERMANENT
    }

    /** What an attempt shows about the other side, as far as the circuit breaker is concerned. */
    enum Health {
        /** It answered in an intelligible way (even to refuse). */
        ALIVE,
        /** It did not answer, or answered that it is failing. */
        UNHEALTHY,
        /** The failure was on the platform's side before anything reached it; it shows nothing. */
        NEUTRAL
    }

    /**
     * The verdict on one attempt.
     *
     * @param kind retry or permanent
     * @param reason the stable key stored and counted
     * @param health what it shows about the other side
     * @param honourRetryAfter whether a Retry-After header of the answer lengthens the wait
     */
    record Verdict(Kind kind, String reason, Health health, boolean honourRetryAfter) {

        boolean retry() {
            return kind == Kind.RETRY;
        }
    }

    private RetryPolicy() {
    }

    /** The verdict on an attempt that got no answer. */
    static Verdict failure(TestReason failure) {
        return switch (failure) {
            case TIMED_OUT -> retry(failure.key(), Health.UNHEALTHY);
            case CONNECT_FAILED -> retry(failure.key(), Health.UNHEALTHY);
            case NAME_NOT_FOUND -> retry(failure.key(), Health.UNHEALTHY);
            // A connection that was opened and then broke or gave nonsense: a reset, the most common outage.
            case RESPONSE_INVALID -> retry(failure.key(), Health.UNHEALTHY);
            case CREDENTIAL_STORE_UNAVAILABLE -> retry(failure.key(), Health.NEUTRAL);
            case TLS_FAILED -> permanent(failure.key(), Health.UNHEALTHY);
            // File transfer (ADR-0237). A server whose key is not the pinned one is not "down": it is not ours, and the
            // connection is stopped until a person looks. Directories and rights are answers of a living server that
            // no waiting will change. A refused file operation (a full disk, a failing store) may pass.
            case HOST_KEY_MISMATCH -> permanent(failure.key(), Health.NEUTRAL);
            case PATH_NOT_FOUND, PERMISSION_DENIED -> permanent(failure.key(), Health.ALIVE);
            case SSH_FAILED -> permanent(failure.key(), Health.UNHEALTHY);
            case FILE_FAILED -> retry(failure.key(), Health.UNHEALTHY);
            case ADDRESS_NOT_ALLOWED, REDIRECT_REFUSED, RESPONSE_TOO_LARGE, CREDENTIAL_MISSING, CREDENTIAL_UNUSABLE,
                    AUTHENTICATION_REJECTED, CHANGED_DURING_TEST, CREDENTIAL_CHANGED -> permanent(failure.key(),
                    Health.NEUTRAL);
            default -> permanent(FlowReason.UNEXPECTED.key(), Health.NEUTRAL);
        };
    }

    /**
     * The verdict on an attempt that got a status.
     *
     * @param status the status code
     * @param accepted whether the connector counts the answer as success for this operation
     */
    static Verdict status(int status, boolean accepted) {
        if (accepted) {
            return new Verdict(Kind.PERMANENT, "ok", Health.ALIVE, false);
        }
        return switch (status) {
            case 408 -> retry(FlowReason.HTTP_TIMEOUT.key(), Health.UNHEALTHY);
            case 429 -> new Verdict(Kind.RETRY, FlowReason.THROTTLED_BY_SERVICE.key(), Health.ALIVE, true);
            case 500 -> retry(FlowReason.SERVER_ERROR.key(), Health.UNHEALTHY);
            case 502 -> retry(FlowReason.BAD_GATEWAY.key(), Health.UNHEALTHY);
            case 503 -> new Verdict(Kind.RETRY, FlowReason.UNAVAILABLE.key(), Health.UNHEALTHY, true);
            case 504 -> retry(FlowReason.GATEWAY_TIMEOUT.key(), Health.UNHEALTHY);
            case 401, 403 -> permanent(TestReason.AUTHENTICATION_REJECTED.key(), Health.ALIVE);
            default -> {
                if (status >= 300 && status < 400) {
                    yield permanent(TestReason.REDIRECT_REFUSED.key(), Health.ALIVE);
                }
                yield permanent(FlowReason.REJECTED.key(), Health.ALIVE);
            }
        };
    }

    /** The class of a status for the log: never the number of an answer that was not read. */
    static String statusClass(int status) {
        if (status >= 200 && status < 300) {
            return "2xx";
        }
        if (status >= 300 && status < 400) {
            return "3xx";
        }
        if (status >= 400 && status < 500) {
            return "4xx";
        }
        return status >= 500 && status < 600 ? "5xx" : "none";
    }

    /**
     * The wait after the {@code failedAttempts}-th failed attempt:
     * {@code min(maxDelay, initial x multiplier^(n-1) + random x jitter x initial x multiplier^(n-1))}, never below the
     * initial wait; and when the other side asked for a wait with Retry-After, never shorter than that, up to the
     * ceiling.
     *
     * @param failedAttempts how many attempts have failed, at least 1
     * @param random a number from 0 up to but not including 1
     * @param retryAfterSeconds the wait the other side asked for, or null
     */
    static Duration delay(IntegrationProperties.Retry retry, int failedAttempts, double random,
            Integer retryAfterSeconds) {
        int exponent = Math.max(0, failedAttempts - 1);
        double cap = retry.maxDelay().toMillis();
        // Capped before the jitter is taken: an enormous attempt number must not turn 0 x infinity into NaN.
        double base = Math.min(cap, retry.initialDelay().toMillis() * Math.pow(retry.multiplier(), exponent));
        double jitter = Math.min(Math.max(random, 0.0), 0.999999) * retry.jitter() * base;
        long computed = Math.max(retry.initialDelay().toMillis(), (long) Math.min(cap, base + jitter));
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            long asked = Math.min(retry.retryAfterCeiling().toMillis(), retryAfterSeconds * 1000L);
            computed = Math.max(computed, asked);
        }
        return Duration.ofMillis(computed);
    }

    /** Whether another attempt is allowed after {@code attemptsMade} attempts. */
    static boolean mayTryAgain(IntegrationProperties.Retry retry, int attemptsMade) {
        return attemptsMade < retry.maxAttempts();
    }

    private static Verdict retry(String reason, Health health) {
        return new Verdict(Kind.RETRY, reason, health, false);
    }

    private static Verdict permanent(String reason, Health health) {
        return new Verdict(Kind.PERMANENT, reason, health, false);
    }
}
