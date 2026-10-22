package app.platform.sheets.internal;

import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
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

    /** A request was refused; {@code reason} is one of a few fixed words, never a code that names a place. */
    void refused(String operation, String reason) {
        meters.counter("platform.sheets.versions", "operation", operation, "result", reason).increment();
    }

    private TenantId tenantId() {
        return contexts.current().map(TenantContext::tenantId).orElse(null);
    }
}
