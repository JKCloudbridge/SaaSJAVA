package app.platform.publicapi.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the public API (Sprint 30, ADR-0244 to ADR-0248). The limits of the data (page size, bulk size, the
 * filter and the scan limit) are the settings of the record store, which the public API shares; only what belongs to
 * the public door is here.
 *
 * @param enabled whether the public API answers; when false every address under the prefix, the description included,
 *        is answered {@code NOT_FOUND} (the kill switch, {@code PLATFORM_PUBLIC_API_ENABLED})
 * @param idempotency how long the outcome of a keyed write is kept
 * @param limits the size of what the public API reads from a caller
 */
@ConfigurationProperties("platform.publicapi")
record PublicApiProperties(@DefaultValue("true") boolean enabled, @DefaultValue Idempotency idempotency,
        @DefaultValue Limits limits) {

    /**
     * The memory of keyed writes.
     *
     * @param retention how long a key keeps its outcome (default 24 hours; at least one minute, at most seven days)
     */
    record Idempotency(@DefaultValue("24h") Duration retention) {

        Idempotency {
            if (retention.compareTo(Duration.ofMinutes(1)) < 0 || retention.compareTo(Duration.ofDays(7)) > 0) {
                throw new IllegalArgumentException(
                        "platform.publicapi.idempotency.retention must be one minute to seven days");
            }
        }
    }

    /**
     * What a request may weigh. A body is refused as soon as it is over the limit, announced or counted (a body sent
     * in pieces is counted as it is read), so a caller cannot make the server hold more than this.
     *
     * @param maxBodyBytes the largest body of a create, a change or a query (default 1 MiB)
     * @param maxBulkBodyBytes the largest body of a bulk call (default 8 MiB)
     */
    record Limits(@DefaultValue("1048576") long maxBodyBytes, @DefaultValue("8388608") long maxBulkBodyBytes) {

        Limits {
            if (maxBodyBytes < 1024 || maxBodyBytes > 67_108_864L || maxBulkBodyBytes < 1024
                    || maxBulkBodyBytes > 67_108_864L) {
                throw new IllegalArgumentException("platform.publicapi.limits: a size is outside its range");
            }
        }
    }
}
