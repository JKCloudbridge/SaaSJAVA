package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.integration.internal.RetryPolicy.Health;
import app.platform.integration.internal.RetryPolicy.Kind;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * How a webhook delivery is retried (Sprint 32, ADR-0279): the classification and the schedule are those of Sprint 29,
 * read from the kind of failure and never from what the receiver said, and the settings of the webhooks have the
 * documented defaults and ceilings.
 */
class WebhookRetryTest {

    private static final IntegrationProperties.Retry RETRY = IntegrationProperties.Reliability.defaults().retry();

    private static WebhookProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("platform.webhooks",
                WebhookProperties.class);
    }

    @Test
    void aTemporaryAnswerIsTriedAgainAndAPermanentOneIsNot() {
        for (int status : new int[] {408, 429, 500, 502, 503, 504}) {
            assertThat(RetryPolicy.status(status, false).kind()).as("status " + status).isEqualTo(Kind.RETRY);
        }
        // 410 Gone means the receiver has asked not to be told; every other refusal is not mended by waiting.
        for (int status : new int[] {400, 401, 403, 404, 405, 410, 413, 422, 301, 302}) {
            assertThat(RetryPolicy.status(status, false).kind()).as("status " + status).isEqualTo(Kind.PERMANENT);
        }
    }

    @Test
    void aFailureToReachTheAddressIsTriedAgainExceptForOnesThatNothingWillMend() {
        assertThat(RetryPolicy.failure(TestReason.TIMED_OUT).kind()).isEqualTo(Kind.RETRY);
        assertThat(RetryPolicy.failure(TestReason.CONNECT_FAILED).kind()).isEqualTo(Kind.RETRY);
        assertThat(RetryPolicy.failure(TestReason.NAME_NOT_FOUND).kind()).isEqualTo(Kind.RETRY);
        assertThat(RetryPolicy.failure(TestReason.RESPONSE_INVALID).kind()).isEqualTo(Kind.RETRY);
        assertThat(RetryPolicy.failure(TestReason.TLS_FAILED).kind()).isEqualTo(Kind.PERMANENT);
        assertThat(RetryPolicy.failure(TestReason.ADDRESS_NOT_ALLOWED).kind()).isEqualTo(Kind.PERMANENT);
        assertThat(RetryPolicy.failure(TestReason.REDIRECT_REFUSED).kind()).isEqualTo(Kind.PERMANENT);
        assertThat(RetryPolicy.failure(TestReason.CREDENTIAL_MISSING).kind()).isEqualTo(Kind.PERMANENT);
    }

    @Test
    void aPlatformSideFailureShowsNothingAboutTheAddress() {
        assertThat(RetryPolicy.failure(TestReason.ADDRESS_NOT_ALLOWED).health()).isEqualTo(Health.NEUTRAL);
        assertThat(RetryPolicy.failure(TestReason.CREDENTIAL_MISSING).health()).isEqualTo(Health.NEUTRAL);
        assertThat(RetryPolicy.failure(TestReason.TIMED_OUT).health()).isEqualTo(Health.UNHEALTHY);
        assertThat(RetryPolicy.status(404, false).health()).isEqualTo(Health.ALIVE);
        assertThat(RetryPolicy.status(503, false).health()).isEqualTo(Health.UNHEALTHY);
    }

    @Test
    void theWaitGrowsStaysUnderTheCeilingAndHonoursRetryAfterUpToItsOwnCeiling() {
        Duration previous = Duration.ZERO;
        for (int failed = 1; failed <= 12; failed++) {
            Duration wait = RetryPolicy.delay(RETRY, failed, 0.0, null);
            assertThat(wait).isGreaterThanOrEqualTo(previous).isLessThanOrEqualTo(RETRY.maxDelay());
            previous = wait;
        }
        assertThat(RetryPolicy.delay(RETRY, 1, 0.0, null)).isEqualTo(RETRY.initialDelay());
        assertThat(RetryPolicy.delay(RETRY, 1, 0.0, 120)).isEqualTo(Duration.ofSeconds(120));
        assertThat(RetryPolicy.delay(RETRY, 1, 0.0, 10_000_000)).isEqualTo(RETRY.retryAfterCeiling());
    }

    @Test
    void aDeliveryHasAStatedNumberOfAttemptsAndThenEndsAsADeadLetter() {
        assertThat(RetryPolicy.mayTryAgain(RETRY, RETRY.maxAttempts() - 1)).isTrue();
        assertThat(RetryPolicy.mayTryAgain(RETRY, RETRY.maxAttempts())).isFalse();
    }

    @Test
    void theWebhookSettingsHaveTheDocumentedDefaults() {
        WebhookProperties defaults = bind(Map.of());

        assertThat(defaults.enabled()).isTrue();
        assertThat(defaults.maxSubscriptions()).isEqualTo(50);
        assertThat(defaults.disableAfter()).isEqualTo(Duration.ofDays(7));
        assertThat(defaults.maxOverlap()).isEqualTo(Duration.ofHours(24));
        assertThat(defaults.delivery().maxInFlight()).isEqualTo(10);
        assertThat(defaults.delivery().perSecond()).isEqualTo(20);
        assertThat(defaults.delivery().perMinute()).isEqualTo(1200);
        assertThat(defaults.worker().perOrganization()).isEqualTo(20);
        assertThat(defaults.retry()).isEqualTo(RETRY).as("the schedule of Sprint 29");
        assertThat(defaults.circuit()).isEqualTo(IntegrationProperties.Reliability.defaults().circuit());
        assertThat(defaults.retention().deliveries()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void aSettingOutsideItsCeilingDoesNotStartTheApplication() {
        assertThatThrownBy(() -> bind(Map.of("platform.webhooks.retention.deliveries", "12h")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bind(Map.of("platform.webhooks.max-subscriptions", "0")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bind(Map.of("platform.webhooks.max-overlap", "48h")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bind(Map.of("platform.webhooks.disable-after", "10m")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bind(Map.of("platform.webhooks.delivery.max-in-flight", "0")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
