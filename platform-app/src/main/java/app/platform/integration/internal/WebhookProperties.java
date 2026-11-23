package app.platform.integration.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the webhooks (Sprint 32, ADR-0274 to ADR-0279). Every number has a ceiling and a safe default that is
 * generous enough for an organization's own use, so the {@code local} profile starts unchanged. The retry arithmetic
 * and
 * the circuit breaker are those of Sprint 29, with the same defaults, and are classes the integrations share.
 *
 * @param enabled whether this instance runs the webhook worker and the handler of events ({@code
 *        PLATFORM_WEBHOOKS_ENABLED}, the kill switch); the queue is shared, so one instance is enough. With it off
 *        nothing is queued and nothing is sent, and the screens still work
 * @param maxSubscriptions how many subscriptions one organization may have (default 50)
 * @param disableAfter how long a subscription may fail without one success before the platform switches it off and
 *        tells the administrators (default 7 days); nothing is deleted
 * @param maxOverlap the longest two signing secrets may work at once while one is replaced (default 24 hours)
 * @param worker how the queue is worked
 * @param delivery how one delivery is made and how fast one subscription is called
 * @param retry the retry arithmetic of Sprint 29
 * @param circuit the circuit breaker of Sprint 29, one for each subscription
 * @param queue the bounds of the queue of one subscription
 * @param retention how long the record of a finished delivery is kept
 * @param test how a verification ping is bounded
 */
@ConfigurationProperties("platform.webhooks")
record WebhookProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("50") int maxSubscriptions,
        @DefaultValue("7d") Duration disableAfter,
        @DefaultValue("24h") Duration maxOverlap,
        @DefaultValue Worker worker,
        @DefaultValue Delivery delivery,
        @DefaultValue IntegrationProperties.Retry retry,
        @DefaultValue IntegrationProperties.Circuit circuit,
        @DefaultValue Queue queue,
        @DefaultValue Retention retention,
        @DefaultValue Test test) {

    WebhookProperties {
        if (maxSubscriptions < 1 || maxSubscriptions > 500 || disableAfter.compareTo(Duration.ofHours(1)) < 0
                || disableAfter.compareTo(Duration.ofDays(90)) > 0 || maxOverlap.compareTo(Duration.ZERO) < 0
                || maxOverlap.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("platform.webhooks.* is out of range");
        }
    }

    /**
     * How the queue is worked.
     *
     * @param pollInterval how often the worker looks for due deliveries (default 1 second)
     * @param batchSize how many deliveries one look may claim (default 100)
     * @param perOrganization how many of one organization's deliveries one look may claim, so that one organization
     *        with thousands of events does not starve another (default 20)
     * @param lease how long a claimed delivery is held before another instance may take it (default 2 minutes)
     * @param maxConcurrentSends how many deliveries one instance sends at once (default 16)
     */
    record Worker(@DefaultValue("1s") Duration pollInterval, @DefaultValue("100") int batchSize,
            @DefaultValue("20") int perOrganization, @DefaultValue("2m") Duration lease,
            @DefaultValue("16") int maxConcurrentSends) {

        Worker {
            if (pollInterval.compareTo(Duration.ofMillis(50)) < 0 || pollInterval.compareTo(Duration.ofMinutes(1)) > 0
                    || batchSize < 1 || batchSize > 1000 || perOrganization < 1 || perOrganization > 100
                    || lease.compareTo(Duration.ofSeconds(15)) < 0 || lease.compareTo(Duration.ofHours(1)) > 0
                    || maxConcurrentSends < 1 || maxConcurrentSends > 256) {
                throw new IllegalArgumentException("platform.webhooks.worker.* is out of range");
            }
        }
    }

    /**
     * How one delivery is made.
     *
     * @param sendTimeout the longest one attempt may take, from the first lookup to the last byte (default 10 seconds)
     * @param maxInFlight how many calls to one subscription's address may be under way at once (default 10)
     * @param perSecond how many deliveries a second one subscription's address is sent at most (default 20)
     */
    record Delivery(@DefaultValue("10s") Duration sendTimeout, @DefaultValue("10") int maxInFlight,
            @DefaultValue("20") int perSecond) {

        Delivery {
            if (sendTimeout.compareTo(Duration.ofSeconds(1)) < 0 || sendTimeout.compareTo(Duration.ofSeconds(60)) > 0
                    || maxInFlight < 1 || maxInFlight > 100 || perSecond < 1 || perSecond > 1000) {
                throw new IllegalArgumentException("platform.webhooks.delivery.* is out of range");
            }
        }

        /** The rate as the bucket takes it: deliveries a minute. */
        int perMinute() {
            return perSecond * 60;
        }
    }

    /**
     * The bounds of the queue.
     *
     * @param maxPerSubscription the most deliveries of one subscription that may wait at once; an event that arrives
     *        when the queue is full is recorded as a dead letter with the reason {@code queue_full} (default 10,000)
     * @param idleRecheck how long a delivery waits before it is looked at again when its subscription is switched off,
     *        its member cannot act or its circuit is open (default 30 seconds)
     */
    record Queue(@DefaultValue("10000") int maxPerSubscription, @DefaultValue("30s") Duration idleRecheck) {

        Queue {
            if (maxPerSubscription < 1 || maxPerSubscription > 1_000_000
                    || idleRecheck.compareTo(Duration.ofSeconds(1)) < 0
                    || idleRecheck.compareTo(Duration.ofMinutes(30)) > 0) {
                throw new IllegalArgumentException("platform.webhooks.queue.* is out of range");
            }
        }
    }

    /**
     * How long finished work is kept.
     *
     * @param deliveries how long the record of a finished delivery and its attempts are kept; never fewer than one day,
     *        which the database also holds (default 30 days)
     * @param batch the most rows one clean-up removes (default 500)
     */
    record Retention(@DefaultValue("30d") Duration deliveries, @DefaultValue("500") int batch) {

        Retention {
            if (deliveries.compareTo(Duration.ofDays(1)) < 0 || deliveries.compareTo(Duration.ofDays(400)) > 0
                    || batch < 1 || batch > 5000) {
                throw new IllegalArgumentException("platform.webhooks.retention.* is out of range");
            }
        }
    }

    /**
     * How a verification ping is bounded.
     *
     * @param minInterval the shortest time between two pings of one subscription (default 5 seconds)
     * @param maxConcurrent how many pings one instance sends at once (default 4)
     */
    record Test(@DefaultValue("5s") Duration minInterval, @DefaultValue("4") int maxConcurrent) {

        Test {
            if (minInterval.compareTo(Duration.ofSeconds(1)) < 0 || minInterval.compareTo(Duration.ofMinutes(10)) > 0
                    || maxConcurrent < 1 || maxConcurrent > 64) {
                throw new IllegalArgumentException("platform.webhooks.test.* is out of range");
            }
        }
    }
}
