package app.platform.sheets.internal;

import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes the audit record and the metrics of a change to a sheet, in the transaction of the change (FA3, ADR-0186).
 *
 * <p><strong>Facts only.</strong> The audit record names the object and the record the sheet belongs to, the number of
 * the version, the row version, the size and the counts of what the grid holds. Never a label, a picklist value, a
 * cell, the name of the record or anything else a person typed: a grid is the organization's text (ADR-0012). The hash
 * of the grid is a fact about it, not its content, and is kept so that two versions can be told apart or told to be the
 * same. A metric tag is one of a few fixed words, never an object or an identifier.
 */
@Component
class SheetChanges {

    private final AuditRecorder audit;
    private final TenantContexts contexts;
    private final MeterRegistry meters;

    SheetChanges(AuditRecorder audit, TenantContexts contexts, MeterRegistry meters) {
        this.audit = audit;
        this.contexts = contexts;
        this.meters = meters;
    }

    /** A new draft was started. {@code basedOn} is the number it was copied from, or 0 for a blank first version. */
    void created(UUID actor, String object, UUID owner, int versionNo, int basedOn, int dimension) {
        audit.record(AuditRecord.of("sheets.version.created", AuditOutcome.SUCCESS).forUser(actor)
                .inTenant(tenantId()).onObject(object, owner.toString()).with("version_no", Integer.toString(versionNo))
                .with("based_on", Integer.toString(basedOn)).with("dimension", Integer.toString(dimension)));
        meters.counter("platform.sheets.versions", "operation", "create", "result", "ok").increment();
    }

    /**
     * A draft was made from an uploaded spreadsheet file (UI3B). The size of the file, the hash and the counts say that
     * it happened and how much; nothing the file held is written down, not even the name of the file or of its sheet.
     *
     * @param format {@code xlsx} or {@code csv}
     * @param fileBytes the size of the file as it arrived
     * @param leftOut how many things the file held that the sheet does not
     */
    void imported(UUID actor, String object, UUID owner, int versionNo, String format, int fileBytes,
            SheetGridValidator.Checked checked, int leftOut) {
        SheetGrid grid = checked.grid();
        audit.record(base("sheets.version.imported", actor, object, owner, versionNo).with("format", format)
                .with("file_bytes", Integer.toString(fileBytes)).with("bytes", Integer.toString(checked.bytes()))
                .with("hash", SheetGridWriter.hex(checked.hash()))
                .with("columns", Integer.toString(grid.columns().size()))
                .with("rows", Integer.toString(grid.rows().size()))
                .with("cells", Integer.toString(grid.cells().size()))
                .with("merges", Integer.toString(grid.merges().size())).with("left_out", Integer.toString(leftOut)));
        meters.counter("platform.sheets.versions", "operation", "import", "result", "ok").increment();
        DistributionSummary.builder("platform.sheets.import.bytes").baseUnit("bytes").register(meters)
                .record(fileBytes);
    }

    /** A draft was saved. */
    void saved(UUID actor, String object, UUID owner, int versionNo, long version, SheetGridValidator.Checked checked) {
        SheetGrid grid = checked.grid();
        audit.record(AuditRecord.of("sheets.version.saved", AuditOutcome.SUCCESS).forUser(actor)
                .inTenant(tenantId()).onObject(object, owner.toString()).with("version_no", Integer.toString(versionNo))
                .with("version", Long.toString(version)).with("bytes", Integer.toString(checked.bytes()))
                .with("hash", SheetGridWriter.hex(checked.hash())).with("columns",
                        Integer.toString(grid.columns().size())).with("rows", Integer.toString(grid.rows().size()))
                .with("header_rows", Integer.toString(grid.headerRows().size()))
                .with("cells", Integer.toString(grid.cells().size()))
                .with("merges", Integer.toString(grid.merges().size())));
        meters.counter("platform.sheets.versions", "operation", "save", "result", "ok").increment();
        DistributionSummary.builder("platform.sheets.grid.bytes").baseUnit("bytes").register(meters)
                .record(checked.bytes());
    }

    /** The draft went to review under a request (FA4). {@code actor} is null when the platform sent it. */
    void submitted(UUID actor, String object, UUID owner, int versionNo, UUID requestId, long bytes) {
        audit.record(base("sheets.version.submitted", actor, object, owner, versionNo)
                .with("request", requestId.toString()).with("bytes", Long.toString(bytes)));
        meters.counter("platform.sheets.versions", "operation", "submit", "result", "ok").increment();
    }

    /** A review ended (FA4). {@code status} is the status the version now has. */
    void reviewed(UUID actor, String object, UUID owner, int versionNo, String status) {
        audit.record(base("sheets.version.reviewed", actor, object, owner, versionNo).with("status", status));
        meters.counter("platform.sheets.versions", "operation", "review", "result", status.toLowerCase(Locale.ROOT))
                .increment();
    }

    /** A version became the one in use; {@code replaced} is the number it replaced, or 0 (FA4). */
    void published(UUID actor, String object, UUID owner, int versionNo, int replaced) {
        audit.record(base("sheets.version.published", actor, object, owner, versionNo)
                .with("replaced", Integer.toString(replaced)));
        meters.counter("platform.sheets.versions", "operation", "publish", "result", "ok").increment();
    }

    /** The version in use is approved again, not in use (FA4). */
    void unpublished(UUID actor, String object, UUID owner, int versionNo) {
        audit.record(base("sheets.version.unpublished", actor, object, owner, versionNo));
        meters.counter("platform.sheets.versions", "operation", "unpublish", "result", "ok").increment();
    }

    /** The version in use was retired (FA4). */
    void retired(UUID actor, String object, UUID owner, int versionNo) {
        audit.record(base("sheets.version.retired", actor, object, owner, versionNo));
        meters.counter("platform.sheets.versions", "operation", "retire", "result", "ok").increment();
    }

    /**
     * An approver changed the grid of a version in review (FA4, ADR-0174 section 4). The size, the counts and the hash
     * say that it changed and how much; the grid itself is never written down.
     */
    void reviewEdited(UUID actor, String object, UUID owner, int versionNo, long version, int edits,
            SheetGridValidator.Checked checked) {
        SheetGrid grid = checked.grid();
        audit.record(base("sheets.version.review_edited", actor, object, owner, versionNo)
                .with("version", Long.toString(version)).with("review_edits", Integer.toString(edits))
                .with("bytes", Integer.toString(checked.bytes())).with("hash", SheetGridWriter.hex(checked.hash()))
                .with("cells", Integer.toString(grid.cells().size())));
        meters.counter("platform.sheets.versions", "operation", "review_edit", "result", "ok").increment();
    }

    private AuditRecord base(String type, UUID actor, String object, UUID owner, int versionNo) {
        AuditRecord record = AuditRecord.of(type, AuditOutcome.SUCCESS).inTenant(tenantId())
                .onObject(object, owner.toString()).with("version_no", Integer.toString(versionNo));
        return actor == null ? record : record.forUser(actor);
    }

    /** A request was refused; {@code reason} is one of a few fixed words, never a code that names a place. */
    void refused(String operation, String reason) {
        meters.counter("platform.sheets.versions", "operation", operation, "result", reason).increment();
    }

    private TenantId tenantId() {
        return contexts.current().map(TenantContext::tenantId).orElse(null);
    }
}
