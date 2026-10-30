package app.platform.integration.internal;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the connector framework (Sprint 26, ADR-0208, ADR-0210). The defaults suit a deployment.
 *
 * @param limits what one organization may make
 * @param egress how the platform reaches an external system
 * @param test how a connection test is bounded
 * @param flow how records are sent and received (Sprint 28, ADR-0220, ADR-0221)
 */
@ConfigurationProperties("platform.integration")
record IntegrationProperties(@DefaultValue Limits limits, @DefaultValue Egress egress, @DefaultValue Testing test,
        @DefaultValue Flow flow) {

    /**
     * What one organization may make.
     *
     * @param maxConnections live connections per organization (default 20)
     */
    record Limits(@DefaultValue("20") int maxConnections) {

        Limits {
            if (maxConnections < 1 || maxConnections > 200) {
                throw new IllegalArgumentException("platform.integration.limits.* is out of range");
            }
        }
    }

    /**
     * How the platform reaches an external system.
     *
     * @param connectTimeout how long to wait for the connection to open (default 3 seconds)
     * @param readTimeout how long to wait for the next bytes of an answer (default 5 seconds)
     * @param maxHeaderBytes the most the platform reads of an answer; more is refused (default 16 KiB)
     * @param developmentHosts exact host names or addresses that may be used over plain HTTP and may be private.
     *        Empty by default and refused in the {@code prod} profile: it exists so a developer's machine and the
     *        automated tests can reach a service of their own
     */
    record Egress(@DefaultValue("3s") Duration connectTimeout, @DefaultValue("5s") Duration readTimeout,
            @DefaultValue("16384") int maxHeaderBytes, @DefaultValue List<String> developmentHosts) {

        Egress {
            if (connectTimeout.compareTo(Duration.ofMillis(100)) < 0 || connectTimeout.compareTo(
                    Duration.ofSeconds(30)) > 0 || readTimeout.compareTo(Duration.ofMillis(100)) < 0
                    || readTimeout.compareTo(Duration.ofSeconds(30)) > 0 || maxHeaderBytes < 1024
                    || maxHeaderBytes > 65536) {
                throw new IllegalArgumentException("platform.integration.egress.* is out of range");
            }
            developmentHosts = List.copyOf(developmentHosts);
        }

        /** The development hosts, lower case, as a set. */
        Set<String> developmentHostSet() {
            return developmentHosts.stream().map(host -> host.strip().toLowerCase(Locale.ROOT))
                    .filter(host -> !host.isEmpty()).collect(Collectors.toUnmodifiableSet());
        }
    }

    /**
     * How a connection test is bounded.
     *
     * @param timeout the longest a whole test may take, from the first lookup to the last byte (default 10 seconds)
     * @param minInterval the shortest time between two tests of one connection (default 5 seconds)
     * @param maxConcurrent how many tests one instance runs at once (default 4)
     */
    record Testing(@DefaultValue("10s") Duration timeout, @DefaultValue("5s") Duration minInterval,
            @DefaultValue("4") int maxConcurrent) {

        Testing {
            if (timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(Duration.ofSeconds(60)) > 0
                    || minInterval.compareTo(Duration.ofSeconds(1)) < 0
                    || minInterval.compareTo(Duration.ofMinutes(10)) > 0 || maxConcurrent < 1
                    || maxConcurrent > 64) {
                throw new IllegalArgumentException("platform.integration.test.* is out of range");
            }
        }
    }

    /**
     * How records are sent and received (Sprint 28).
     *
     * @param maxConcurrentSends how many sends one instance makes at once; the rest are recorded as not made (default
     * 16)
     * @param sendTimeout the longest one send may take, from the first lookup to the last byte (default 10 seconds)
     * @param maxRequestBytes the most bytes of a body the platform sends (default 256 KiB)
     * @param maxResponseBytes the most bytes of an answer the platform reads (default 64 KiB)
     * @param maxInboundBytes the most bytes of a change the receiver accepts (default 256 KiB)
     * @param replayWindow how far a signed call's time may be from now, and how long its nonce is kept (default 5
     * minutes)
     * @param inboundPerMinutePerSource how many calls one source address may make to the receiver in a minute
     *        (default 120)
     * @param deliveryRetention how long the record of a finished send or receive is kept (default 30 days)
     * @param staleAfter how long a send may stay pending before it is shown as interrupted (default 10 minutes)
     */
    record Flow(@DefaultValue("16") int maxConcurrentSends, @DefaultValue("10s") Duration sendTimeout,
            @DefaultValue("262144") int maxRequestBytes, @DefaultValue("65536") int maxResponseBytes,
            @DefaultValue("262144") int maxInboundBytes, @DefaultValue("5m") Duration replayWindow,
            @DefaultValue("120") int inboundPerMinutePerSource, @DefaultValue("30d") Duration deliveryRetention,
            @DefaultValue("10m") Duration staleAfter) {

        Flow {
            if (maxConcurrentSends < 1 || maxConcurrentSends > 256
                    || sendTimeout.compareTo(Duration.ofSeconds(1)) < 0
                    || sendTimeout.compareTo(Duration.ofSeconds(60)) > 0
                    || maxRequestBytes < 1024 || maxRequestBytes > 4_194_304 || maxResponseBytes < 1024
                    || maxResponseBytes > 1_048_576 || maxInboundBytes < 1024 || maxInboundBytes > 4_194_304
                    || replayWindow.compareTo(Duration.ofMinutes(1)) < 0
                    || replayWindow.compareTo(Duration.ofMinutes(30)) > 0 || inboundPerMinutePerSource < 1
                    || inboundPerMinutePerSource > 100_000
                    || deliveryRetention.compareTo(Duration.ofDays(1)) < 0
                    || deliveryRetention.compareTo(Duration.ofDays(400)) > 0
                    || staleAfter.compareTo(Duration.ofMinutes(1)) < 0
                    || staleAfter.compareTo(Duration.ofHours(24)) > 0) {
                throw new IllegalArgumentException("platform.integration.flow.* is out of range");
            }
        }
    }
}
