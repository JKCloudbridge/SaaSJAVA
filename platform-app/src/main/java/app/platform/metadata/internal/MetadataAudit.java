package app.platform.metadata.internal;

import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes the audit records of object and field definitions (ADR-0058): one place that names the events and decides what
 * each carries. A record is about an object, or about one field of it, by API name ({@code onObject}); it says what
 * kind of thing changed in short words and never repeats what a person typed: labels and descriptions are text the
 * organization chose, so a record names that they changed, not what they changed to. Records are written in the
 * transaction of the change, so they commit or roll back with it; a refusal is written by the caller after its
 * transaction ended.
 */
@Component
class MetadataAudit {

    private final AuditRecorder recorder;
    private final TenantContexts contexts;

    MetadataAudit(AuditRecorder recorder, TenantContexts contexts) {
        this.recorder = recorder;
        this.contexts = contexts;
    }

    void objectCreated(UUID actor, String object) {
        write(AuditRecord.of("metadata.object.created", AuditOutcome.SUCCESS).forUser(actor).onObject(object, null));
    }

    void objectUpdated(UUID actor, String object, String changed) {
        write(AuditRecord.of("metadata.object.updated", AuditOutcome.SUCCESS).forUser(actor)
                .onObject(object, null).with("changed", changed));
    }

    void objectDeleted(UUID actor, String object, int fieldsRemoved, int recordTypesRemoved,
            int permissionLinesEnded) {
        write(AuditRecord.of("metadata.object.deleted", AuditOutcome.SUCCESS).forUser(actor).onObject(object, null)
                .with("fields_removed", Integer.toString(fieldsRemoved))
                .with("record_types_removed", Integer.toString(recordTypesRemoved))
                .with("permission_lines_ended", Integer.toString(permissionLinesEnded)));
    }

    void recordTypeCreated(UUID actor, String object, String recordType, boolean active, boolean defaultType) {
        write(AuditRecord.of("metadata.recordtype.created", AuditOutcome.SUCCESS).forUser(actor)
                .onObject(object + "." + recordType, null).with("active", Boolean.toString(active))
                .with("default", Boolean.toString(defaultType)));
    }

    void recordTypeUpdated(UUID actor, String object, String recordType, String changed) {
        write(AuditRecord.of("metadata.recordtype.updated", AuditOutcome.SUCCESS).forUser(actor)
                .onObject(object + "." + recordType, null).with("changed", changed));
    }

    void recordTypeDeleted(UUID actor, String object, String recordType) {
        write(AuditRecord.of("metadata.recordtype.deleted", AuditOutcome.SUCCESS).forUser(actor)
                .onObject(object + "." + recordType, null));
    }

    // ---- lifecycle: change sets and releases (ADR-0066, ADR-0067) ----

    void changeSetCreated(UUID actor, UUID changeSet) {
        write(AuditRecord.of("metadata.changeset.created", AuditOutcome.SUCCESS).forUser(actor)
                .with("change_set", changeSet.toString()));
    }

    void changeSetChanged(UUID actor, UUID changeSet, String what, String kind, int position) {
        write(AuditRecord.of("metadata.changeset.changed", AuditOutcome.SUCCESS).forUser(actor)
                .with("change_set", changeSet.toString()).with("what", what).with("kind", kind)
                .with("position", Integer.toString(position)));
    }

    void changeSetChecked(UUID actor, UUID changeSet, String how, boolean valid, int problems) {
        write(AuditRecord.of("metadata.changeset.checked", AuditOutcome.SUCCESS).forUser(actor)
                .with("change_set", changeSet.toString()).with("how", how).with("valid", Boolean.toString(valid))
                .with("problems", Integer.toString(problems)));
    }

    void changeSetDiscarded(UUID actor, UUID changeSet) {
        write(AuditRecord.of("metadata.changeset.discarded", AuditOutcome.SUCCESS).forUser(actor)
                .with("change_set", changeSet.toString()));
    }

    /** Written after the transaction of the refused publication ended. */
    void publishRefused(UUID actor, String kind, UUID changeSet, int problems) {
        AuditRecord record = AuditRecord.of("metadata.publish.refused", AuditOutcome.DENIED).forUser(actor)
                .because("invalid_metadata").with("kind", kind).with("problems", Integer.toString(problems));
        write(changeSet == null ? record : record.with("change_set", changeSet.toString()));
    }

    void released(UUID actor, long release, String kind, UUID changeSet, int items) {
        AuditRecord record = AuditRecord.of("metadata.release.published", AuditOutcome.SUCCESS).forUser(actor)
                .with("release", Long.toString(release)).with("kind", kind).with("items", Integer.toString(items));
        write(changeSet == null ? record : record.with("change_set", changeSet.toString()));
    }

    void rolledBack(UUID actor, long release, long undone, int items) {
        write(AuditRecord.of("metadata.release.rolledback", AuditOutcome.SUCCESS).forUser(actor)
                .with("release", Long.toString(release)).with("undone", Long.toString(undone))
                .with("items", Integer.toString(items)));
    }

    void fieldCreated(UUID actor, String object, String field, String type, boolean required, boolean unique) {
        write(AuditRecord.of("metadata.field.created", AuditOutcome.SUCCESS).forUser(actor)
                .onObject(object + "." + field, null).with("type", type).with("required", Boolean.toString(required))
                .with("unique", Boolean.toString(unique)));
    }

    void fieldUpdated(UUID actor, String object, String field, String changed, String before, String after) {
        AuditRecord record = AuditRecord.of("metadata.field.updated", AuditOutcome.SUCCESS).forUser(actor)
                .onObject(object + "." + field, null).with("changed", changed);
        write(before == null ? record : record.changing(before, after));
    }

    void fieldDeleted(UUID actor, String object, String field, int permissionLinesEnded) {
        write(AuditRecord.of("metadata.field.deleted", AuditOutcome.SUCCESS).forUser(actor)
                .onObject(object + "." + field, null)
                .with("permission_lines_ended", Integer.toString(permissionLinesEnded)));
    }

    /** An organization tried to change something the platform defines. Written after the transaction ended. */
    void protectedChangeRefused(UUID actor, String action, String object) {
        write(AuditRecord.of("metadata.change.refused", AuditOutcome.DENIED).forUser(actor)
                .because("protected_definition").onObject(object, null).with("action", action));
    }

    private void write(AuditRecord record) {
        TenantId tenant = contexts.current().map(TenantContext::tenantId).orElse(null);
        recorder.record(record.inTenant(tenant));
    }
}
