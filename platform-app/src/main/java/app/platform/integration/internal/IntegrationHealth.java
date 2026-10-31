package app.platform.integration.internal;

import app.platform.sharedkernel.health.AutomationHealthSource;
import app.platform.sharedkernel.health.HealthItem;
import app.platform.sharedkernel.health.HealthSection;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The part of the health of automation that belongs to the integrations (Sprint 29, ADR-0235): what waits to be sent,
 * what is set aside, how old the longest wait is. Counts only, with the place to look; no name of a connection, a
 * record or an outside system.
 *
 * <p>For one organization it reads that organization's rows under its row level security. Across organizations it
 * reports what the outbound worker last measured for its own gauges, so asking costs nothing more.
 */
@Component
class IntegrationHealth implements AutomationHealthSource {

    private static final Duration STALE = Duration.ofMinutes(10);
    private static final String PLACE = "/setup/integrations";

    private final ConnectionStore connections;
    private final DeliveryStore deliveries;
    private final OutboundWorker worker;
    private final PollStore polls;
    private final FileLedgerStore files;

    IntegrationHealth(ConnectionStore connections, DeliveryStore deliveries, OutboundWorker worker, PollStore polls,
            FileLedgerStore files) {
        this.connections = connections;
        this.deliveries = deliveries;
        this.worker = worker;
        this.polls = polls;
        this.files = files;
    }

    @Override
    @Transactional(readOnly = true)
    public List<HealthSection> organization() {
        long waiting = 0;
        long deadLetters = 0;
        long oldestSeconds = 0;
        long filesWaiting = 0;
        long filesSetAside = 0;
        java.time.Instant now = java.time.Instant.now();
        for (ConnectionStore.Connection connection : connections.list()) {
            DeliveryStore.Counts counts = deliveries.counts(connection.id());
            waiting += counts.pending() + counts.sending() + counts.waitingRemote();
            deadLetters += counts.deadLettered();
            filesWaiting += polls.find(connection.id()).map(PollStore.Poll::filesWaiting).orElse(0);
            filesSetAside += files.counts(connection.id()).setAside();
            if (counts.oldestOpen() != null && counts.oldestOpen().isBefore(now)) {
                oldestSeconds = Math.max(oldestSeconds, Duration.between(counts.oldestOpen(), now).toSeconds());
            }
        }
        return List.of(new HealthSection("integrations", "Integrations", List.of(
                new HealthItem("integrations.waiting", "Changes waiting to be sent to an outside system", waiting,
                        false, PLACE),
                new HealthItem("integrations.deadLettered", "Changes set aside after their attempts", deadLetters,
                        deadLetters > 0, PLACE),
                new HealthItem("integrations.oldestSeconds", "Seconds the longest wait has lasted", oldestSeconds,
                        oldestSeconds > STALE.toSeconds(), null),
                new HealthItem("integrations.filesWaiting", "Files waiting to be picked up from a file server",
                        filesWaiting, false, PLACE),
                new HealthItem("integrations.filesSetAside", "Files set aside for a person to look at", filesSetAside,
                        filesSetAside > 0, PLACE))));
    }

    @Override
    public List<HealthSection> platform() {
        OutboundWorker.Sample sample = worker.sample();
        return List.of(new HealthSection("integrations", "Integrations", List.of(
                new HealthItem("integrations.waiting", "Changes waiting to be sent to an outside system",
                        sample.open(), false, null),
                new HealthItem("integrations.deadLettered", "Changes set aside after their attempts",
                        sample.deadLetters(), sample.deadLetters() > 0, null),
                new HealthItem("integrations.oldestSeconds", "Seconds the longest wait has lasted",
                        sample.oldestSeconds(), sample.oldestSeconds() > STALE.toSeconds(), null))));
    }
}
