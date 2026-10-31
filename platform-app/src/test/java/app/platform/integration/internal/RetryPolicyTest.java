package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.internal.RetryPolicy.Health;
import app.platform.integration.internal.RetryPolicy.Kind;
import app.platform.integration.internal.RetryPolicy.Verdict;
import java.time.Duration;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The classification and the arithmetic of retries (Sprint 29, ADR-0227): pure, so the bounds are properties. */
class RetryPolicyTest {

    private static final IntegrationProperties.Retry RETRY = IntegrationProperties.Reliability.defaults().retry();

    @ParameterizedTest
    @CsvSource({
            "408, RETRY, http_timeout, UNHEALTHY",
            "429, RETRY, throttled_by_service, ALIVE",
            "500, RETRY, server_error, UNHEALTHY",
            "502, RETRY, bad_gateway, UNHEALTHY",
            "503, RETRY, unavailable, UNHEALTHY",
            "504, RETRY, gateway_timeout, UNHEALTHY",
            "400, PERMANENT, rejected, ALIVE",
            "404, PERMANENT, rejected, ALIVE",
            "409, PERMANENT, rejected, ALIVE",
            "422, PERMANENT, rejected, ALIVE",
            "401, PERMANENT, authentication_rejected, ALIVE",
            "403, PERMANENT, authentication_rejected, ALIVE",
            "301, PERMANENT, redirect_refused, ALIVE",
            "501, PERMANENT, rejected, ALIVE"})
    void aStatusIsClassifiedFromItsNumberAlone(int status, Kind kind, String reason, Health health) {
        Verdict verdict = RetryPolicy.status(status, false);

        assertThat(verdict.kind()).isEqualTo(kind);
        assertThat(verdict.reason()).isEqualTo(reason);
        assertThat(verdict.health()).isEqualTo(health);
    }

    @Test
    void anAnswerTheConnectorAcceptsIsNeverARetry() {
        assertThat(RetryPolicy.status(200, true).retry()).isFalse();
        assertThat(RetryPolicy.status(202, true).health()).isEqualTo(Health.ALIVE);
    }

    @ParameterizedTest
    @CsvSource({
            "TIMED_OUT, RETRY, UNHEALTHY",
            "CONNECT_FAILED, RETRY, UNHEALTHY",
            "NAME_NOT_FOUND, RETRY, UNHEALTHY",
            "RESPONSE_INVALID, RETRY, UNHEALTHY",
            "CREDENTIAL_STORE_UNAVAILABLE, RETRY, NEUTRAL",
            "TLS_FAILED, PERMANENT, UNHEALTHY",
            "ADDRESS_NOT_ALLOWED, PERMANENT, NEUTRAL",
            "REDIRECT_REFUSED, PERMANENT, NEUTRAL",
            "RESPONSE_TOO_LARGE, PERMANENT, NEUTRAL",
            "CREDENTIAL_MISSING, PERMANENT, NEUTRAL",
            "CREDENTIAL_UNUSABLE, PERMANENT, NEUTRAL",
            "AUTHENTICATION_REJECTED, PERMANENT, NEUTRAL"})
    void aFailureIsClassifiedFromItsKindNotFromAnyText(TestReason failure, Kind kind, Health health) {
        Verdict verdict = RetryPolicy.failure(failure);

        assertThat(verdict.kind()).isEqualTo(kind);
        assertThat(verdict.health()).isEqualTo(health);
        assertThat(verdict.reason()).matches("[a-z_]{1,40}");
    }

    @Test
    void anUnlistedFailureIsPermanent() {
        Verdict verdict = RetryPolicy.failure(TestReason.UNEXPECTED);

        assertThat(verdict.kind()).isEqualTo(Kind.PERMANENT);
        assertThat(verdict.reason()).isEqualTo("unexpected");
    }

    @Test
    void everyReasonOfEveryAnswerIsAStableKeyWithWords() {
        for (int status = 100; status < 600; status++) {
            Verdict verdict = RetryPolicy.status(status, false);
            assertThat(FlowReason.wordsFor(verdict.reason())).as("words for status class of %s", status).isNotBlank();
        }
        for (TestReason failure : TestReason.values()) {
            assertThat(FlowReason.wordsFor(RetryPolicy.failure(failure).reason())).isNotBlank();
        }
    }

    @Test
    void theClassOfAStatusNeverRevealsTheNumber() {
        assertThat(RetryPolicy.statusClass(204)).isEqualTo("2xx");
        assertThat(RetryPolicy.statusClass(302)).isEqualTo("3xx");
        assertThat(RetryPolicy.statusClass(418)).isEqualTo("4xx");
        assertThat(RetryPolicy.statusClass(503)).isEqualTo("5xx");
        assertThat(RetryPolicy.statusClass(0)).isEqualTo("none");
    }

    @Test
    void theWaitGrowsFromTheInitialDelayToTheCap() {
        assertThat(RetryPolicy.delay(RETRY, 1, 0.0, null)).isEqualTo(Duration.ofSeconds(5));
        assertThat(RetryPolicy.delay(RETRY, 2, 0.0, null)).isEqualTo(Duration.ofSeconds(10));
        assertThat(RetryPolicy.delay(RETRY, 3, 0.0, null)).isEqualTo(Duration.ofSeconds(20));
        assertThat(RetryPolicy.delay(RETRY, 30, 0.0, null)).isEqualTo(RETRY.maxDelay());
    }

    @Test
    void retryAfterLengthensTheWaitButOnlyUpToTheCeiling() {
        assertThat(RetryPolicy.delay(RETRY, 1, 0.0, 120)).isEqualTo(Duration.ofSeconds(120));
        assertThat(RetryPolicy.delay(RETRY, 1, 0.0, 1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(RetryPolicy.delay(RETRY, 1, 0.0, 1_000_000)).isEqualTo(RETRY.retryAfterCeiling());
        assertThat(RetryPolicy.delay(RETRY, 1, 0.0, -5)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void theAttemptsAreBoundedBySetting() {
        assertThat(RetryPolicy.mayTryAgain(RETRY, 4)).isTrue();
        assertThat(RetryPolicy.mayTryAgain(RETRY, 5)).isFalse();
    }

    @Property
    void theWaitIsNeverBelowTheInitialDelayNorAboveTheLargerOfTheCapAndTheCeiling(
            @ForAll @IntRange(min = 1, max = 100_000) int failed,
            @ForAll @DoubleRange(min = 0.0, max = 0.99) double random,
            @ForAll @IntRange(min = -10, max = 5_000_000) int retryAfter) {
        Duration wait = RetryPolicy.delay(RETRY, failed, random, retryAfter);

        assertThat(wait).isGreaterThanOrEqualTo(RETRY.initialDelay());
        assertThat(wait).isLessThanOrEqualTo(RETRY.maxDelay().compareTo(RETRY.retryAfterCeiling()) > 0
                ? RETRY.maxDelay() : RETRY.retryAfterCeiling());
    }

    @Property
    void withoutRetryAfterAWaitIsNeverShorterThanTheOneBefore(
            @ForAll @IntRange(min = 1, max = 60) int failed,
            @ForAll @DoubleRange(min = 0.0, max = 0.99) double random) {
        Duration now = RetryPolicy.delay(RETRY, failed, random, null);
        Duration earlier = RetryPolicy.delay(RETRY, failed, 0.0, null);
        Duration next = RetryPolicy.delay(RETRY, failed + 1, 0.0, null);

        assertThat(now).isBetween(earlier, RETRY.maxDelay());
        // The multiplier is at least 1 + jitter, so the next base wait is not shorter than this wait with its jitter.
        assertThat(next).isGreaterThanOrEqualTo(earlier);
        assertThat(now).isLessThanOrEqualTo(RETRY.maxDelay());
    }
}
