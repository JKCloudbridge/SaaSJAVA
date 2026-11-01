package app.platform.data.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the memory of keyed writes (Sprint 30, ADR-0246). How long an outcome is kept is the setting of the
 * public API that asks for it; this is only how the expired ones are removed.
 *
 * @param enabled whether this instance removes expired outcomes (two instances doing it at once is harmless)
 * @param interval how often the clean-up runs (default 15 minutes)
 * @param batchSize the most rows one pass removes per organization (default 1000)
 */
@ConfigurationProperties("platform.data.idempotency")
record IdempotencyProperties(@DefaultValue("true") boolean enabled, @DefaultValue("15m") Duration interval,
        @DefaultValue("1000") int batchSize) {

    IdempotencyProperties {
        if (interval.compareTo(Duration.ofSeconds(1)) < 0 || batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException(
                    "platform.data.idempotency: an interval of at least one second and a batch size of 1 to 10000");
        }
    }
}
