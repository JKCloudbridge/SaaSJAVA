package app.platform.data.internal;

import app.platform.sharedkernel.events.EventEnvelope;
import app.platform.sharedkernel.events.EventHandler;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Starts the recalculation of stored formula results after a published change (Sprint 16A, ADR-0113). Every release is
 * announced through the outbox in the transaction that published it; the instance that claims the event marks the
 * objects whose formulas changed and asks for the pass to start. Nothing depends on the event arriving: a query on a
 * formula field is refused from the moment the formulas changed (the fingerprints are compared, not an event), and the
 * sweep of {@link FormulaRecalculation} finds an object whose formulas changed without one.
 */
@Component
class FormulaRecalculationEvents implements EventHandler {

    private final FormulaRecalculation recalculation;

    FormulaRecalculationEvents(FormulaRecalculation recalculation) {
        this.recalculation = recalculation;
    }

    @Override
    public String consumerName() {
        return "data.formula-recalculation";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(IndexPolicyEvents.EVENT_TYPE);
    }

    @Override
    public void handle(EventEnvelope event) {
        recalculation.noteChanges(FormulaRecalculationStore.Trigger.PUBLISH);
        recalculation.request(event.tenantId());
    }
}
