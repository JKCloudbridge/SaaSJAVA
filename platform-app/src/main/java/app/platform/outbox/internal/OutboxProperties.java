package app.platform.outbox.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the outbox relay (ADR-0016). The defaults suit a deployment; tests shorten the times.
 *
 * @param enabled whether this instance runs the relay (every instance may; they coordinate through the database)
 * @param pollInterval pause between two polls when the last poll found nothing
 * @param batchSize events claimed per poll
 * @param lease how long a claimed event belongs to the claiming instance before another may take it over; must exceed
 *        the longest time a batch takes to handle
 * @param maxAttempts how many times an event is handled before it becomes a dead letter
 * @param backoffInitial the wait before the second attempt; it doubles with every further attempt
 * @param backoffMax the longest wait between two attempts
 * @param deliveredRetention how long delivered events stay in the table (for diagnosis) before they are removed
 * @param markerRetention how long the idempotency markers stay; must exceed {@code deliveredRetention} and the
 *        longest time an event can be retried, or a very late redelivery would be handled twice
 */
@ConfigurationProperties("platform.outbox")
record OutboxProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("10") int batchSize,
        @DefaultValue("2m") Duration lease,
        @DefaultValue("8") int maxAttempts,
        @DefaultValue("5s") Duration backoffInitial,
        @DefaultValue("15m") Duration backoffMax,
        @DefaultValue("7d") Duration deliveredRetention,
        @DefaultValue("30d") Duration markerRetention) {

    OutboxProperties {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("platform.outbox.batch-size must be between 1 and 500");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("platform.outbox.max-attempts must be at least 1");
        }
        if (pollInterval.isNegative() || pollInterval.isZero() || lease.isNegative() || lease.isZero()
                || backoffInitial.isNegative() || backoffInitial.isZero() || backoffMax.compareTo(backoffInitial) < 0) {
            throw new IllegalArgumentException("platform.outbox times must be positive and "
                    + "backoff-max must not be smaller than backoff-initial");
        }
        if (markerRetention.compareTo(deliveredRetention) <= 0) {
            throw new IllegalArgumentException(
                    "platform.outbox.marker-retention must be longer than delivered-retention");
        }
    }
}
