package app.platform.data.internal;

import app.platform.data.BackgroundWrites;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sharedkernel.audit.AuditSource;
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
        write("created", actor, object, id, version, fields, null);
    }

    void updated(UUID actor, String object, UUID id, long version, List<String> fields) {
        write("updated", actor, object, id, version, fields, null);
    }

    /**
     * A record created by a workflow (ADR-0139): no person to blame, so the audit record names the workflow and the
     * execution (names and identifiers) and the event carries the chain (ADR-0140).
     */
    void createdByWorkflow(BackgroundWrites.Source source, String object, UUID id, long version,
            List<String> fields) {
        write("created", null, object, id, version, fields, source);
    }

    /** A record changed by a workflow, as {@link #createdByWorkflow}. */
    void updatedByWorkflow(BackgroundWrites.Source source, String object, UUID id, long version,
            List<String> fields) {
        write("updated", null, object, id, version, fields, source);
    }

    void deleted(UUID actor, String object, UUID id, long version) {
        write("deleted", actor, object, id, version, List.of(), null);
    }

    void restored(UUID actor, String object, UUID id, long version) {
        write("restored", actor, object, id, version, List.of(), null);
    }

    void purged(UUID actor, String object, UUID id, long version) {
        write("purged", actor, object, id, version, List.of(), null);
    }

    /**
     * A record removed by the clean-up of the recycle bin: the event of a permanent delete, with no person behind it.
     * The audit record of the pass counts them ({@link #retentionPass}).
     */
    void purgedByRetention(String object, UUID id) {
        events.publish(new NewEvent("record.purged", payload(object, id, null, List.of())));
    }

    // ---- sharing and ownership (Sprint 17, ADR-0117): identifiers and kinds, never a name or a value ----

    // ---- bulk calls and imports (Sprint 17, ADR-0122, ADR-0123): kinds and counts, never a value ----

    /** One audit record for a whole bulk call, written whether or not it was saved. */
    void bulkSummary(UUID actor, String object, String operation, int records, int succeeded, int failed,
            boolean allOrNone, boolean committed) {
        audit.record(AuditRecord.of("data.bulk.completed", AuditOutcome.SUCCESS).forUser(actor).inTenant(tenantId())
                .onObject(object, null).with("operation", operation).with("records", Integer.toString(records))
                .with("succeeded", Integer.toString(succeeded)).with("failed", Integer.toString(failed))
                .with("all_or_none", Boolean.toString(allOrNone)).with("committed", Boolean.toString(committed)));
    }

    /** An import was sent and queued. */
    void importStarted(UUID actor, String object, UUID job, String operation, boolean dryRun, long bytes) {
        audit.record(AuditRecord.of("data.import.started", AuditOutcome.SUCCESS).forUser(actor).inTenant(tenantId())
                .onObject(object, null).with("import", job.toString()).with("operation", operation)
                .with("dry_run", Boolean.toString(dryRun)).with("bytes", Long.toString(bytes)));
    }

    /** An import ended, in whatever state: the counts, never a value of the file. */
    void importFinished(UUID actor, String object, UUID job, String operation, boolean dryRun, String state,
            int processed, int created, int updated, int failed) {
        audit.record(AuditRecord.of("data.import.finished", AuditOutcome.SUCCESS).forUser(actor).inTenant(tenantId())
                .onObject(object, null).with("import", job.toString()).with("operation", operation)
                .with("dry_run", Boolean.toString(dryRun)).with("state", state)
                .with("processed", Integer.toString(processed)).with("created", Integer.toString(created))
                .with("updated", Integer.toString(updated)).with("failed", Integer.toString(failed)));
    }

    /** A person asked an import to stop. */
    void importCancelled(UUID actor, String object, UUID job) {
        audit.record(AuditRecord.of("data.import.cancel_requested", AuditOutcome.SUCCESS).forUser(actor)
                .inTenant(tenantId()).onObject(object, null).with("import", job.toString()));
    }

    // ---- files (Sprint 17, ADR-0120): identifiers and a size, never a name or a byte ----

    /** A file was stored on a record. */
    void fileUploaded(UUID actor, String object, UUID record, UUID file, long size) {
        audit.record(AuditRecord.of("data.file.uploaded", AuditOutcome.SUCCESS).forUser(actor).inTenant(tenantId())
                .onObject(object, record.toString()).with("file", file.toString())
                .with("size", Long.toString(size)));
    }

    /** A file was handed out. */
    void fileDownloaded(UUID actor, String object, UUID record, UUID file) {
        audit.record(AuditRecord.of("data.file.downloaded", AuditOutcome.SUCCESS).forUser(actor)
                .inTenant(tenantId()).onObject(object, record.toString()).with("file", file.toString()));
    }

    /** A file was removed from a record. */
    void fileDeleted(UUID actor, String object, UUID record, UUID file) {
        audit.record(AuditRecord.of("data.file.deleted", AuditOutcome.SUCCESS).forUser(actor).inTenant(tenantId())
                .onObject(object, record.toString()).with("file", file.toString()));
    }

    /** The pass that removed bytes whose files are gone and uploads that never finished: counts only. */
    void fileCleanupPass(TenantId tenant, int bytesRemoved, int uploadsExpired) {
        audit.record(AuditRecord.of("retention.file.cleaned", AuditOutcome.SUCCESS).inTenant(tenant)
                .with("bytes_removed", Integer.toString(bytesRemoved))
                .with("uploads_expired", Integer.toString(uploadsExpired)));
    }

    /** A record was shared with a user or a group at a level. */
    void shared(UUID actor, String object, UUID id, String granteeType, String level) {
        audit.record(AuditRecord.of("data.record.shared", AuditOutcome.SUCCESS).forUser(actor).inTenant(tenantId())
                .onObject(object, id.toString()).with("grantee_type", granteeType).with("level", level));
    }

    /** A share was taken back. */
    void unshared(UUID actor, String object, UUID id, String granteeType) {
        audit.record(AuditRecord.of("data.record.unshared", AuditOutcome.SUCCESS).forUser(actor).inTenant(tenantId())
                .onObject(object, id.toString()).with("grantee_type", granteeType));
    }

    /** A record was given to another owner: the change of the record, and an audit record naming both owners. */
    void ownerChanged(UUID actor, String object, UUID id, long version, UUID from, UUID to) {
        audit.record(AuditRecord.of("data.record.owner_changed", AuditOutcome.SUCCESS).forUser(actor)
                .inTenant(tenantId()).onObject(object, id.toString()).with("version", Long.toString(version))
                .with("from_owner", from.toString()).with("to_owner", to.toString()));
        events.publish(new NewEvent("record.updated", payload(object, id, version, List.of("ownerId"))));
    }

    // ---- saved list views (Sprint 19, ADR-0130): the object, the view's identifier and its scope, never a name ----

    /** A list view was saved. */
    void viewCreated(UUID actor, String object, UUID view, String scope) {
        audit.record(AuditRecord.of("data.view.created", AuditOutcome.SUCCESS).forUser(actor).inTenant(tenantId())
                .onObject(object, null).with("view", view.toString()).with("scope", scope));
    }

    /** A list view was changed; {@code scope} is the one it has now. */
    void viewChanged(UUID actor, String object, UUID view, String scope, long version) {
        audit.record(AuditRecord.of("data.view.changed", AuditOutcome.SUCCESS).forUser(actor).inTenant(tenantId())
                .onObject(object, null).with("view", view.toString()).with("scope", scope)
                .with("version", Long.toString(version)));
    }

    /** A list view was removed. */
    void viewRemoved(UUID actor, String object, UUID view, String scope) {
        audit.record(AuditRecord.of("data.view.removed", AuditOutcome.SUCCESS).forUser(actor).inTenant(tenantId())
                .onObject(object, null).with("view", view.toString()).with("scope", scope));
    }

    private TenantId tenantId() {
        return contexts.current().map(TenantContext::tenantId).orElse(null);
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

    private void write(String kind, UUID actor, String object, UUID id, long version, List<String> fields,
            BackgroundWrites.Source source) {
        TenantId tenant = contexts.current().map(TenantContext::tenantId).orElse(null);
        AuditRecord record = AuditRecord.of("data.record." + kind, AuditOutcome.SUCCESS).forUser(actor)
                .inTenant(tenant).onObject(object, id.toString()).with("version", Long.toString(version));
        if (!fields.isEmpty()) {
            record = record.with("field_count", Integer.toString(fields.size()))
                    .with("fields", String.join(",", fields));
        }
        if (source != null) {
            // Names and identifiers only (ADR-0139): which workflow, which run, never a value.
            record = record.from(AuditSource.SYSTEM).with("actor", "platform").with("workflow", source.workflow())
                    .with("execution", source.execution().toString());
        }
        audit.record(record);
        events.publish(new NewEvent("record." + kind, payload(object, id, version, fields, source)));
    }

    private String payload(String object, UUID id, Long version, List<String> fields) {
        return payload(object, id, version, fields, null);
    }

    private String payload(String object, UUID id, Long version, List<String> fields, BackgroundWrites.Source source) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("object", object);
        facts.put("recordId", id.toString());
        if (version != null) {
            facts.put("version", version);
        }
        if (!fields.isEmpty()) {
            facts.put("fields", fields);
        }
        if (source != null && source.chain() != null) {
            // The chain of workflow runs that led here, so the handler can continue it (ADR-0140): identifiers and a
            // number only. A person's write has none, which reads as depth 0.
            Map<String, Object> chain = new LinkedHashMap<>();
            chain.put("root", source.chain().root().toString());
            chain.put("depth", source.chain().depth());
            chain.put("workflows", source.chain().workflows().stream().map(UUID::toString).toList());
            facts.put("chain", chain);
        }
        return json.write(facts);
    }
}
