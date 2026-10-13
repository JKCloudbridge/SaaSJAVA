package app.platform.data.internal;

import app.platform.sharedkernel.events.EventEnvelope;
import app.platform.sharedkernel.events.EventHandler;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Starts the index policy and the link index after a published change (ADR-0101, ADR-0106). Every release is
 * announced through the outbox in the transaction that published it; the instance that claims the event brings the
 * slot assignments in line with the published flags and asks for the fill to start. If the event is lost, late or
 * handled twice nothing breaks: the sweep of {@link IndexBackfill} does the same thing at its interval, and the
 * reconciliation is idempotent.
 */
@Component
class IndexPolicyEvents implements EventHandler {

    /** The event a release writes (the metadata module owns the name; the module graph does not let this one ask). */
    static final String EVENT_TYPE = "metadata.published";

    private final IndexPolicy policy;
    private final IndexBackfill backfill;
    private final LinkBackfill linkFill;

    IndexPolicyEvents(IndexPolicy policy, IndexBackfill backfill, LinkBackfill linkFill) {
        this.policy = policy;
        this.backfill = backfill;
        this.linkFill = linkFill;
    }

    @Override
    public String consumerName() {
        return "data.index-policy";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(EVENT_TYPE);
    }

    @Override
    public void handle(EventEnvelope event) {
        policy.reconcile();
        linkFill.noteFields();
        backfill.request(event.tenantId());
    }
}
