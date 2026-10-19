package app.platform.workflow.internal;

import app.platform.sharedkernel.events.ParkedEvents;
import app.platform.sharedkernel.health.AutomationHealthSource;
import app.platform.sharedkernel.health.HealthItem;
import app.platform.sharedkernel.health.HealthSection;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The part of the health of automation that belongs to workflows (Sprint 25, ADR-0163): what waits, what retries, what
 * is set aside, which events the platform gave up on. Counts only, with the place to look; no name of a record, a
 * workflow or a person.
 *
 * <p>For one organization it reads that organization's rows under its row level security. Across organizations it
 * reports what the dispatcher last measured for its own gauges, so asking costs nothing more.
 */
@Component
class WorkflowHealth implements AutomationHealthSource {

    /** The events the workflow handlers consume: what a parked event of this list means for workflows (ADR-0143). */
    static final Set<String> EVENT_TYPES = Set.of("record.created", "record.updated", "record.deleted",
            "approval.approved", "approval.rejected", "approval.recalled");

    private static final Duration STALE = Duration.ofMinutes(10);
    private static final String HISTORY = "/workflows/executions";

    private final ExecutionStore executions;
    private final ParkedEvents parked;
    private final Dispatcher dispatcher;

    WorkflowHealth(ExecutionStore executions, ParkedEvents parked, Dispatcher dispatcher) {
        this.executions = executions;
        this.parked = parked;
        this.dispatcher = dispatcher;
    }

    @Override
    @Transactional(readOnly = true)
    public List<HealthSection> organization() {
        ExecutionStore.Health health = executions.health(STALE);
        long parkedEvents = parked.count(EVENT_TYPES);
        return List.of(new HealthSection("workflows", "Workflows", List.of(
                new HealthItem("workflows.queued", "Executions waiting to run", health.queued(), false, HISTORY),
                new HealthItem("workflows.running", "Executions running now", health.running(), false, HISTORY),
                new HealthItem("workflows.retrying", "Steps waiting for another attempt", health.retrying(), false,
                        HISTORY),
                new HealthItem("workflows.stale", "Executions waiting longer than 10 minutes", health.stale(),
                        health.stale() > 0, HISTORY),
                new HealthItem("workflows.deadLettered", "Executions set aside for a person", health.deadLettered(),
                        health.deadLettered() > 0, HISTORY),
                new HealthItem("workflows.parkedEvents", "Events the platform gave up delivering", parkedEvents,
                        parkedEvents > 0, HISTORY),
                new HealthItem("workflows.oldestSeconds", "Seconds the longest wait has lasted",
                        health.oldestQueuedSeconds(), health.oldestQueuedSeconds() > STALE.toSeconds(), null))));
    }

    @Override
    public List<HealthSection> platform() {
        Dispatcher.Sample sample = dispatcher.sample();
        return List.of(new HealthSection("workflows", "Workflows", List.of(
                new HealthItem("workflows.queued", "Executions waiting to run", sample.queued(), false, null),
                new HealthItem("workflows.deadLettered", "Executions set aside for a person", sample.deadLettered(),
                        sample.deadLettered() > 0, null),
                new HealthItem("workflows.oldestSeconds", "Seconds the longest wait has lasted",
                        sample.oldestSeconds(), sample.oldestSeconds() > STALE.toSeconds(), null))));
    }
}
