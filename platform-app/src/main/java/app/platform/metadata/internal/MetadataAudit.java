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

    // ---- layouts, record pages, applications and layout assignments (ADR-0069 to ADR-0071) ----

    /**
     * A definition of Sprint 12 was made. {@code kind} is {@code layout}, {@code recordpage}, {@code application} or
     * {@code layoutassignment}; {@code name} is the API name (an assignment: its key); {@code size} counts the
     * sections, components or items. Nothing typed by a person is written.
     */
    void definitionCreated(UUID actor, String kind, String object, String name, int size) {
        write(AuditRecord.of("metadata." + kind + ".created", AuditOutcome.SUCCESS).forUser(actor)
                .onObject(path(object, name), null).with("size", Integer.toString(size)));
    }

    void definitionUpdated(UUID actor, String kind, String object, String name, String changed, int size) {
        write(AuditRecord.of("metadata." + kind + ".updated", AuditOutcome.SUCCESS).forUser(actor)
                .onObject(path(object, name), null).with("changed", changed).with("size", Integer.toString(size)));
    }

    void definitionDeleted(UUID actor, String kind, String object, String name) {
        write(AuditRecord.of("metadata." + kind + ".deleted", AuditOutcome.SUCCESS).forUser(actor)
                .onObject(path(object, name), null));
    }

    /**
     * A script of a custom page was uploaded (ADR-0127): its identifier and size. Neither the content nor the hash is
     * written; the script is text the organization chose.
     */
    void customPageAssetUploaded(UUID actor, UUID asset, long size) {
        write(AuditRecord.of("metadata.custompageasset.uploaded", AuditOutcome.SUCCESS).forUser(actor)
                .with("asset", asset.toString()).with("size", Long.toString(size)));
    }

    // ---- validation rules (ADR-0078) ----

    /** A rule was made: by name and counts only; the condition and the message are text the organization typed. */
    void validationRuleCreated(UUID actor, String object, String name, boolean active, int fieldsUsed) {
        write(AuditRecord.of("metadata.validationrule.created", AuditOutcome.SUCCESS).forUser(actor)
                .onObject(object + "." + name, null).with("active", Boolean.toString(active))
                .with("fields_used", Integer.toString(fieldsUsed)));
    }

    void validationRuleUpdated(UUID actor, String object, String name, String changed, boolean active,
            int fieldsUsed) {
        write(AuditRecord.of("metadata.validationrule.updated", AuditOutcome.SUCCESS).forUser(actor)
                .onObject(object + "." + name, null).with("changed", changed)
                .with("active", Boolean.toString(active)).with("fields_used", Integer.toString(fieldsUsed)));
    }

    void validationRuleDeleted(UUID actor, String object, String name) {
        write(AuditRecord.of("metadata.validationrule.deleted", AuditOutcome.SUCCESS).forUser(actor)
                .onObject(object + "." + name, null));
    }

    // ---- workflows (Sprint 22, ADR-0136): names, the kind of trigger and counts, never a condition or a setting ----

    void workflowCreated(UUID actor, String object, String name, boolean active, String triggerKind, int actions) {
        write(AuditRecord.of("metadata.workflow.created", AuditOutcome.SUCCESS).forUser(actor)
                .onObject(object + "." + name, null).with("active", Boolean.toString(active))
                .with("trigger", triggerKind).with("actions", Integer.toString(actions)));
    }

    void workflowUpdated(UUID actor, String object, String name, String changed, boolean active,
            int definitionVersion) {
        write(AuditRecord.of("metadata.workflow.updated", AuditOutcome.SUCCESS).forUser(actor)
                .onObject(object + "." + name, null).with("changed", changed)
                .with("active", Boolean.toString(active))
                .with("definition_version", Integer.toString(definitionVersion)));
    }

    void workflowActivated(UUID actor, String object, String name, boolean active) {
        write(AuditRecord.of(active ? "metadata.workflow.activated" : "metadata.workflow.deactivated",
                AuditOutcome.SUCCESS).forUser(actor).onObject(object + "." + name, null));
    }

    void workflowDeleted(UUID actor, String object, String name) {
        write(AuditRecord.of("metadata.workflow.deleted", AuditOutcome.SUCCESS).forUser(actor)
                .onObject(object + "." + name, null));
    }

    // ---- approval processes (Sprint 24, ADR-0145): names, counts and flags, never criteria, labels or values ----

    void approvalProcessCreated(UUID actor, String object, String name, boolean active, int steps) {
        write(AuditRecord.of("metadata.approvalprocess.created", AuditOutcome.SUCCESS).forUser(actor)
                .onObject(object + "." + name, null).with("active", Boolean.toString(active))
                .with("steps", Integer.toString(steps)));
    }

    void approvalProcessUpdated(UUID actor, String object, String name, String changed, boolean active,
            int definitionVersion) {
        write(AuditRecord.of("metadata.approvalprocess.updated", AuditOutcome.SUCCESS).forUser(actor)
                .onObject(object + "." + name, null).with("changed", changed)
                .with("active", Boolean.toString(active))
                .with("definition_version", Integer.toString(definitionVersion)));
    }

    void approvalProcessActivated(UUID actor, String object, String name, boolean active) {
        write(AuditRecord.of(active ? "metadata.approvalprocess.activated" : "metadata.approvalprocess.deactivated",
                AuditOutcome.SUCCESS).forUser(actor).onObject(object + "." + name, null));
    }

    void approvalProcessDeleted(UUID actor, String object, String name) {
        write(AuditRecord.of("metadata.approvalprocess.deleted", AuditOutcome.SUCCESS).forUser(actor)
                .onObject(object + "." + name, null));
    }

    /**
     * The audit object key of a definition: the object and the name, joined by a dot. The key of a layout assignment
     * has a tilde between the profile and the record type, which an audit key does not allow, so it becomes a dot.
     */
    private static String path(String object, String name) {
        String clean = name.replace('~', '.');
        return object == null || object.isEmpty() ? clean : object + "." + clean;
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

    void fieldCreated(UUID actor, String object, String field, String type, boolean required, boolean unique,
            boolean indexed) {
        write(AuditRecord.of("metadata.field.created", AuditOutcome.SUCCESS).forUser(actor)
                .onObject(object + "." + field, null).with("type", type).with("required", Boolean.toString(required))
                .with("unique", Boolean.toString(unique)).with("indexed", Boolean.toString(indexed)));
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

    // ---- the organization's own list of a platform picklist (FA2A, ADR-0182): counts only, never a value or label
    // ----

    /** An administrator changed the list or the pairs of a platform picklist (or went back to the platform's list). */
    void fieldValuesChanged(UUID actor, String object, String field, int values, int pairs, boolean reset) {
        write(AuditRecord.of("metadata.fieldvalues.changed", AuditOutcome.SUCCESS).forUser(actor)
                .onObject(object + "." + field, null).with("values", Integer.toString(values))
                .with("pairs", Integer.toString(pairs)).with("reset", Boolean.toString(reset)));
    }

    /** A member added a value (and the pairs it goes with) from the form. */
    void fieldValueAdded(UUID actor, String object, String field, boolean newValue, int pairsAdded) {
        write(AuditRecord.of("metadata.fieldvalues.added", AuditOutcome.SUCCESS).forUser(actor)
                .onObject(object + "." + field, null).with("new_value", Boolean.toString(newValue))
                .with("pairs_added", Integer.toString(pairsAdded)));
    }

    // ---- notification text (Sprint 25, ADR-0155): the kind only, never the title or the body ----

    void notificationTemplateWritten(UUID actor, String kind, boolean created) {
        String action = created ? "metadata.notificationtemplate.created" : "metadata.notificationtemplate.updated";
        write(AuditRecord.of(action, AuditOutcome.SUCCESS).forUser(actor).onObject(kind, null));
    }

    void notificationTemplateReset(UUID actor, String kind) {
        write(AuditRecord.of("metadata.notificationtemplate.reset", AuditOutcome.SUCCESS).forUser(actor)
                .onObject(kind, null));
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
