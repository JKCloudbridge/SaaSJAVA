package app.platform.workflow.internal;

import app.platform.metadata.Metadata;
import app.platform.metadata.WorkflowDefinition;
import app.platform.sharedkernel.events.EventEnvelope;
import app.platform.sharedkernel.events.EventHandler;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Keeps the schedule rows of an organization in step with its published workflows (ADR-0135). Every release is
 * announced through the outbox ({@code metadata.published}); the handler of that event makes a row for every active
 * scheduled workflow that has none, starts a row over when the definition it was planned for has changed, and removes
 * the row of a workflow that was switched off, removed or is no longer scheduled.
 *
 * <p>The state is derived: the published definitions are the truth and the rows can always be rebuilt from them, so
 * handling the event twice, late or out of order changes nothing. Switching a workflow off and on again starts it
 * afresh from the moment it is switched on; nothing is fired for the time it was off.
 */
@Component
class ScheduleReconciler implements EventHandler {

    /** The event a release writes; named here, not imported, because the metadata module keeps it internal. */
    static final String PUBLISHED = "metadata.published";

    private final Metadata metadata;
    private final ScheduleStore schedules;
    private final Clock clock;

    ScheduleReconciler(Metadata metadata, ScheduleStore schedules, Clock clock) {
        this.metadata = metadata;
        this.schedules = schedules;
        this.clock = clock;
    }

    @Override
    public String consumerName() {
        return "workflow.schedules";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(PUBLISHED);
    }

    @Override
    public void handle(EventEnvelope event) {
        reconcile();
    }

    /** Makes the rows say what the published workflows say, in the transaction and tenant context it is called in. */
    void reconcile() {
        Instant now = clock.instant();
        Map<UUID, ScheduleStore.Row> rows = new HashMap<>();
        schedules.all().forEach(row -> rows.put(row.workflowId(), row));
        Set<UUID> wanted = new HashSet<>();
        for (WorkflowDefinition workflow : metadata.workflows()) {
            if (!workflow.active() || !workflow.trigger().kind().scheduled()) {
                continue;
            }
            wanted.add(workflow.id());
            ScheduleStore.Row row = rows.get(workflow.id());
            String kind = workflow.trigger().kind().name();
            if (row == null) {
                Plan plan = firstPlan(workflow.trigger(), now);
                schedules.insert(workflow.id(), kind, workflow.definitionVersion(), plan.next(), plan.state());
            } else if (row.definitionVersion() != workflow.definitionVersion() || !row.trigger().equals(kind)) {
                Plan plan = firstPlan(workflow.trigger(), now);
                schedules.reset(row.id(), kind, workflow.definitionVersion(), plan.next(), plan.state());
            }
        }
        rows.values().stream().filter(row -> !wanted.contains(row.workflowId()))
                .forEach(row -> schedules.delete(row.id()));
    }

    /** When a schedule is first due and in which state it waits. */
    record Plan(Instant next, String state) {
    }

    /**
     * The first plan of a trigger from the moment it is switched on: the next occurrence, or for a one-time moment that
     * has already passed (the workflow ran, or its moment was left behind) a finished schedule that never fires.
     */
    static Plan firstPlan(WorkflowDefinition.Trigger trigger, Instant now) {
        Optional<Instant> next = ScheduleMath.next(trigger, now);
        if (next.isPresent()) {
            return new Plan(next.get(), "WAITING");
        }
        return new Plan(trigger.at().atZone(trigger.timeZone()).toInstant(), "DONE");
    }
}
