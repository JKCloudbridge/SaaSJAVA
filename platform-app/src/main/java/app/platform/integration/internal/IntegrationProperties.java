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
 * @param reliability how sends survive an unreliable outside world (Sprint 29, ADR-0227 to ADR-0236)
 * @param files how files are picked up from a file server (Sprint 29A, ADR-0240 to ADR-0242)
 */
@ConfigurationProperties("platform.integration")
record IntegrationProperties(@DefaultValue Limits limits, @DefaultValue Egress egress, @DefaultValue Testing test,
        @DefaultValue Flow flow, @DefaultValue Reliability reliability, @DefaultValue Files files) {

    /**
     * File flows (Sprint 29A, ADR-0240 to ADR-0242): the bounds of picking up files from a server. Every number has a
     * ceiling, and a connection's own choice (how often to look) can only be inside it. The defaults suit a deployment
     * and need nothing to be set.
     *
     * @param enabled whether this instance picks files up (default true; the work is shared through leases, so one
     *        instance is enough). Sending files is part of the outbound worker and is switched with it
     * @param maxConcurrentPolls how many connections one instance looks at once (default 4)
     * @param lease how long a claimed poll is held before another instance may take it (default 10 minutes)
     * @param deadline how long one poll may run before it stops starting new files (default 5 minutes)
     * @param minInterval the shortest time between two looks at one connection, whatever the connection asks (default
     *        1 minute)
     * @param maxFiles the most files taken in one poll (default 50)
     * @param maxListed the most names read from an inbox in one look (default 2,000)
     * @param maxFileBytes the largest file the platform reads (default 25 MiB); a larger one is set aside
     * @param maxRows the most rows of one file (default 10,000)
     * @param batchRows how many rows are written in one transaction (default 200)
     * @param ledgerRetention how long the record of a finished file is kept; it is also how long a file already
     *        processed is recognised if it comes back (default 30 days)
     */
    record Files(@DefaultValue("true") boolean enabled, @DefaultValue("4") int maxConcurrentPolls,
            @DefaultValue("10m") Duration lease, @DefaultValue("5m") Duration deadline,
            @DefaultValue("1m") Duration minInterval, @DefaultValue("50") int maxFiles,
            @DefaultValue("2000") int maxListed, @DefaultValue("26214400") int maxFileBytes,
            @DefaultValue("10000") int maxRows, @DefaultValue("200") int batchRows,
            @DefaultValue("30d") Duration ledgerRetention) {

        Files {
            if (maxConcurrentPolls < 1 || maxConcurrentPolls > 64 || lease.compareTo(Duration.ofSeconds(30)) < 0
                    || lease.compareTo(Duration.ofHours(2)) > 0 || deadline.compareTo(Duration.ofSeconds(10)) < 0
                    || deadline.compareTo(lease) > 0 || minInterval.compareTo(Duration.ofSeconds(5)) < 0
                    || minInterval.compareTo(Duration.ofHours(1)) > 0 || maxFiles < 1 || maxFiles > 1000
                    || maxListed < maxFiles || maxListed > 100_000 || maxFileBytes < 1024
                    || maxFileBytes > 104_857_600 || maxRows < 1 || maxRows > 1_000_000 || batchRows < 1
                    || batchRows > 5000 || ledgerRetention.compareTo(Duration.ofDays(1)) < 0
                    || ledgerRetention.compareTo(Duration.ofDays(400)) > 0) {
                throw new IllegalArgumentException("platform.integration.files.* is out of range");
            }
        }

        /** All defaults, for a test that builds the settings by hand. */
        static Files defaults() {
            return new Files(true, 4, Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofMinutes(1), 50, 2000,
                    26_214_400, 10_000, 200, Duration.ofDays(30));
        }
    }

    /**
     * How a send survives an unreliable outside world (Sprint 29). Every number has a ceiling, and a per-connection
     * choice made by an administrator can only be inside it.
     *
     * @param enabled whether this instance runs the outbound worker (default true; the queue is shared, so one
     *        instance is enough)
     * @param pollInterval how often the worker looks for due deliveries (default 1 second)
     * @param batchSize how many deliveries one look may claim (default 100)
     * @param perOrganization how many of one organization's deliveries one look may claim (default 20, ADR-0164)
     * @param lease how long a claimed delivery is held before another instance may take it (default 2 minutes)
     * @param retry the retry arithmetic
     * @param circuit the circuit breaker
     * @param queue the bounds of the queue
     * @param rate the limits on sending
     * @param remote how long-running operations are asked about
     */
    record Reliability(@DefaultValue("true") boolean enabled, @DefaultValue("1s") Duration pollInterval,
            @DefaultValue("100") int batchSize, @DefaultValue("20") int perOrganization,
            @DefaultValue("2m") Duration lease, @DefaultValue Retry retry, @DefaultValue Circuit circuit,
            @DefaultValue Queue queue, @DefaultValue Rate rate, @DefaultValue Remote remote) {

        Reliability {
            if (pollInterval.compareTo(Duration.ofMillis(50)) < 0 || pollInterval.compareTo(Duration.ofMinutes(1)) > 0
                    || batchSize < 1 || batchSize > 1000 || perOrganization < 1 || perOrganization > 100
                    || lease.compareTo(Duration.ofSeconds(15)) < 0 || lease.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException("platform.integration.reliability.* is out of range");
            }
        }

        /** All defaults, for a test that builds the settings by hand. */
        static Reliability defaults() {
            return new Reliability(true, Duration.ofSeconds(1), 100, 20, Duration.ofMinutes(2),
                    new Retry(5, Duration.ofSeconds(5), 2.0, Duration.ofMinutes(15), 0.2, Duration.ofHours(1)),
                    new Circuit(5, Duration.ofSeconds(60), 1, Duration.ofMinutes(2)),
                    new Queue(10_000, Duration.ofSeconds(30)), new Rate(6_000, 60_000, 4, 32),
                    new Remote(Duration.ofSeconds(15), Duration.ofHours(1)));
        }
    }

    /**
     * The retry arithmetic: {@code min(maxDelay, initialDelay x multiplier^(n-1) + jitter)} (ADR-0227).
     *
     * @param maxAttempts the most attempts of one delivery before it is dead-lettered (default 5)
     * @param initialDelay the wait after the first failure (default 5 seconds)
     * @param multiplier how much longer each next wait is (default 2)
     * @param maxDelay the longest computed wait (default 15 minutes)
     * @param jitter how much of the wait is added at random, 0 to 1 (default 0.2)
     * @param retryAfterCeiling the longest wait the other side may ask for with Retry-After (default 1 hour)
     */
    record Retry(@DefaultValue("5") int maxAttempts, @DefaultValue("5s") Duration initialDelay,
            @DefaultValue("2.0") double multiplier, @DefaultValue("15m") Duration maxDelay,
            @DefaultValue("0.2") double jitter, @DefaultValue("1h") Duration retryAfterCeiling) {

        Retry {
            if (maxAttempts < 1 || maxAttempts > 20 || initialDelay.compareTo(Duration.ofMillis(10)) < 0
                    || initialDelay.compareTo(Duration.ofHours(1)) > 0 || multiplier < 1.2 || multiplier > 10
                    || maxDelay.compareTo(initialDelay) < 0 || maxDelay.compareTo(Duration.ofHours(24)) > 0
                    || jitter < 0 || jitter > 1 || multiplier < 1 + jitter
                    || retryAfterCeiling.compareTo(Duration.ofSeconds(1)) < 0
                    || retryAfterCeiling.compareTo(Duration.ofHours(24)) > 0) {
                throw new IllegalArgumentException("platform.integration.reliability.retry.* is out of range");
            }
        }
    }

    /**
     * The circuit breaker (ADR-0229).
     *
     * @param failureThreshold how many failures in a row open the circuit (default 5)
     * @param openFor how long it stays open before it lets a probe through (default 60 seconds)
     * @param probes how many sends a half-open circuit lets through at once (default 1)
     * @param probeTimeout how long a probe may take before its slot is freed (default 2 minutes)
     */
    record Circuit(@DefaultValue("5") int failureThreshold, @DefaultValue("60s") Duration openFor,
            @DefaultValue("1") int probes, @DefaultValue("2m") Duration probeTimeout) {

        Circuit {
            if (failureThreshold < 1 || failureThreshold > 100 || openFor.compareTo(Duration.ofMillis(100)) < 0
                    || openFor.compareTo(Duration.ofHours(6)) > 0 || probes < 1 || probes > 10
                    || probeTimeout.compareTo(Duration.ofSeconds(5)) < 0
                    || probeTimeout.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException("platform.integration.reliability.circuit.* is out of range");
            }
        }
    }

    /**
     * The bounds of the queue (ADR-0230).
     *
     * @param maxPerConnection the most deliveries of one connection that may wait at once; a change that arrives when
     *        the queue is full is recorded as a dead letter with the reason {@code queue_full} (default 10,000)
     * @param idleRecheck how long a delivery waits before it is looked at again when its connection is off or its
     *        circuit is forced open (default 30 seconds)
     */
    record Queue(@DefaultValue("10000") int maxPerConnection, @DefaultValue("30s") Duration idleRecheck) {

        Queue {
            if (maxPerConnection < 1 || maxPerConnection > 1_000_000 || idleRecheck.compareTo(Duration.ofSeconds(1)) < 0
                    || idleRecheck.compareTo(Duration.ofMinutes(30)) > 0) {
                throw new IllegalArgumentException("platform.integration.reliability.queue.* is out of range");
            }
        }
    }

    /**
     * The limits on sending (ADR-0232). A connection's own limits can be lower than these and never higher.
     *
     * @param defaultPerMinute sends a connection may start in a minute when nothing else is set (default 6,000)
     * @param maxPerMinute the highest rate an administrator may set (default 60,000)
     * @param defaultConcurrent sends of one connection under way at once when nothing else is set (default 4)
     * @param maxConcurrent the highest concurrency an administrator may set (default 32)
     */
    record Rate(@DefaultValue("6000") int defaultPerMinute, @DefaultValue("60000") int maxPerMinute,
            @DefaultValue("4") int defaultConcurrent, @DefaultValue("32") int maxConcurrent) {

        Rate {
            if (defaultPerMinute < 1 || maxPerMinute < defaultPerMinute || maxPerMinute > 600_000
                    || defaultConcurrent < 1 || maxConcurrent < defaultConcurrent || maxConcurrent > 256) {
                throw new IllegalArgumentException("platform.integration.reliability.rate.* is out of range");
            }
        }
    }

    /**
     * Long-running operations (ADR-0233).
     *
     * @param pollInterval how long to wait before asking about a job again, before jitter (default 15 seconds)
     * @param deadline how long a job may take before the delivery gives up on it (default 1 hour); it also bounds the
     *        number of questions, which are at least one interval apart
     */
    record Remote(@DefaultValue("15s") Duration pollInterval, @DefaultValue("1h") Duration deadline) {

        Remote {
            if (pollInterval.compareTo(Duration.ofMillis(100)) < 0 || pollInterval.compareTo(Duration.ofHours(1)) > 0
                    || deadline.compareTo(Duration.ofSeconds(10)) < 0 || deadline.compareTo(Duration.ofDays(7)) > 0) {
                throw new IllegalArgumentException("platform.integration.reliability.remote.* is out of range");
            }
        }
    }

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
