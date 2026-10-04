package app.platform.audit.internal;

import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditSource;
import app.platform.sharedkernel.events.EventEnvelope;
import app.platform.sharedkernel.events.EventHandler;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the tenant lifecycle events of the outbox into audit records (Sprint 2 carried this to Sprint 9, ADR-0054).
 *
 * <p>The record is the <em>fact</em> that the organization changed state ({@code tenant.lifecycle.suspended}, with the
 * state before and after). The platform administrator's <em>action</em> that caused it is another fact with another
 * actor and a typed reason, and is audited by the platform console as {@code platform.organization.suspended}: the two
 * describe different things and are never the same record twice. A record is written once per event: the relay marks
 * the
 * event as handled in the same transaction, and the unique {@code source_event_id} of the table refuses a second
 * record for the same event even if that mark were lost. Events older than the outbox's retention are not replayed.
 */
@Component
class TenantLifecycleAudit implements EventHandler {

    static final String CONSUMER = "audit.tenant-lifecycle";
    private static final String PREFIX = "tenant.";

    private final AuditStore store;
    private final JsonMapper json = JsonMapper.builder().build();

    TenantLifecycleAudit(AuditStore store) {
        this.store = store;
    }

    @Override
    public String consumerName() {
        return CONSUMER;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("tenant.provisioned", "tenant.activated", "tenant.suspended", "tenant.reinstated",
                "tenant.deactivated");
    }

    @Override
    public void handle(EventEnvelope event) {
        JsonNode payload = json.readTree(event.payload());
        String before = payload.path("from").asString(null);
        String after = payload.path("to").asString(null);
        AuditRecord record = new AuditRecord("tenant.lifecycle." + event.type().substring(PREFIX.length()),
                AuditOutcome.SUCCESS, event.userId(), event.tenantId(), null, java.util.Map.of(), null, null, before,
                after, AuditSource.EVENT);
        // A failure throws: the event is retried (the contract of an event handler), unlike a recorder's write.
        store.write(record, event.eventId());
    }
}
