package app.platform.data.internal;

import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sharedkernel.events.EventPublisher;
import app.platform.sharedkernel.events.NewEvent;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes the audit record and the outbox event of a change to a record, in the transaction of the change, so that all
 * three commit or none does (ADR-0089).
 *
 * <p><strong>Facts only.</strong> Both say which object, which record, which kind of change, which version and the
 * <em>names</em> of the fields that were named in the change. Never a value, never a label, never anything a person
 * typed into a record: a record is typed text, an audit value is a short key (ADR-0054), and an event may be read in a
 * dead-letter review (ADR-0016). A field-by-field history with old and new values is a separate, opt-in feature of a
 * later sprint.
 */
@Component
class RecordChanges {

    private final AuditRecorder audit;
    private final EventPublisher events;
    private final RecordJson json;
    private final TenantContexts contexts;

    RecordChanges(AuditRecorder audit, EventPublisher events, RecordJson json, TenantContexts contexts) {
        this.audit = audit;
        this.events = events;
        this.json = json;
        this.contexts = contexts;
    }

    void created(UUID actor, String object, UUID id, long version, List<String> fields) {
        write("created", actor, object, id, version, fields);
    }

    void updated(UUID actor, String object, UUID id, long version, List<String> fields) {
        write("updated", actor, object, id, version, fields);
    }

    void deleted(UUID actor, String object, UUID id, long version) {
        write("deleted", actor, object, id, version, List.of());
    }

    void restored(UUID actor, String object, UUID id, long version) {
        write("restored", actor, object, id, version, List.of());
    }

    void purged(UUID actor, String object, UUID id, long version) {
        write("purged", actor, object, id, version, List.of());
    }

    /**
     * A record removed by the clean-up of the recycle bin: the event of a permanent delete, with no person behind it.
     * The audit record of the pass counts them ({@link #retentionPass}).
     */
    void purgedByRetention(String object, UUID id) {
        events.publish(new NewEvent("record.purged", payload(object, id, null, List.of())));
    }

    /** One audit record for a whole pass of the clean-up in one organization. */
    void retentionPass(TenantId tenant, int removed) {
        audit.record(AuditRecord.of("retention.record.purged", AuditOutcome.SUCCESS).inTenant(tenant)
                .with("removed", Integer.toString(removed)).with("source", "recycle_bin"));
    }


    /**
     * A record that went to the recycle bin together with the record a person removed (a detail with its master): the
     * event of a delete with nobody to blame for this one in particular. The audit record of the removal counts them
     * ({@link #removalSummary}).
     */
    void removedWithAnother(String object, UUID id, long version) {
        events.publish(new NewEvent("record.deleted", payload(object, id, version, List.of())));
    }

    /** A record that came back with the master it was removed with. */
    void restoredWithAnother(String object, UUID id, long version) {
        events.publish(new NewEvent("record.restored", payload(object, id, version, List.of())));
    }

    /**
     * A pointer emptied because the record it pointed at was removed (the behaviour "clear"): the event of a change of
     * the record, by the name of the field.
     */
    void pointerCleared(String object, UUID id, long version, String field) {
        events.publish(new NewEvent("record.updated", payload(object, id, version, List.of(field))));
    }

    /** One audit record for a removal that took other records with it or changed them: counts, never values. */
    void removalSummary(UUID actor, String object, UUID id, int removedWith, int pointersCleared) {
        TenantId tenant = contexts.current().map(TenantContext::tenantId).orElse(null);
        audit.record(AuditRecord.of("data.record.removal", AuditOutcome.SUCCESS).forUser(actor).inTenant(tenant)
                .onObject(object, id.toString()).with("removed_with", Integer.toString(removedWith))
                .with("pointers_cleared", Integer.toString(pointersCleared)));
    }

    /** One audit record for a restore that brought other records back. */
    void restoreSummary(UUID actor, String object, UUID id, int restoredWith, int pointersCleared) {
        TenantId tenant = contexts.current().map(TenantContext::tenantId).orElse(null);
        audit.record(AuditRecord.of("data.record.restoration", AuditOutcome.SUCCESS).forUser(actor).inTenant(tenant)
                .onObject(object, id.toString()).with("restored_with", Integer.toString(restoredWith))
                .with("pointers_cleared", Integer.toString(pointersCleared)));
    }

    private void write(String kind, UUID actor, String object, UUID id, long version, List<String> fields) {
        TenantId tenant = contexts.current().map(TenantContext::tenantId).orElse(null);
        AuditRecord record = AuditRecord.of("data.record." + kind, AuditOutcome.SUCCESS).forUser(actor)
                .inTenant(tenant).onObject(object, id.toString()).with("version", Long.toString(version));
        if (!fields.isEmpty()) {
            record = record.with("field_count", Integer.toString(fields.size()))
                    .with("fields", String.join(",", fields));
        }
        audit.record(record);
        events.publish(new NewEvent("record." + kind, payload(object, id, version, fields)));
    }

    private String payload(String object, UUID id, Long version, List<String> fields) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("object", object);
        facts.put("recordId", id.toString());
        if (version != null) {
            facts.put("version", version);
        }
        if (!fields.isEmpty()) {
            facts.put("fields", fields);
        }
        return json.write(facts);
    }
}
