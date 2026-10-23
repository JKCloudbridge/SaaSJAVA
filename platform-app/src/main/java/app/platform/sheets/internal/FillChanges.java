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
 * Writes the audit record and the metrics of a change to a fill-in, in the transaction of the change (FF1, ADR-0192).
 *
 * <p><strong>Facts only.</strong> The audit record names the header the fill-in belongs to, the version of the sheet it
 * is pinned to, the row version, the size and the counts of what it holds. Never a value, a column label or anything
 * else a person typed: the cells are the organization's text (ADR-0012). The hash of the cells is a fact about them,
 * not their content, and is kept so that two states can be told apart or told to be the same. A metric tag is one of a
 * few fixed words, never an object or an identifier.
 */
@Component
class FillChanges {

    private final AuditRecorder audit;
    private final TenantContexts contexts;
    private final MeterRegistry meters;

    FillChanges(AuditRecorder audit, TenantContexts contexts, MeterRegistry meters) {
        this.audit = audit;
        this.contexts = contexts;
        this.meters = meters;
    }

    /** A fill-in was started on a version of a sheet. */
    void started(UUID actor, String object, UUID fill, int versionNo) {
        audit.record(base("sheets.fill.started", actor, object, fill).with("version_no", Integer.toString(versionNo)));
        meters.counter("platform.sheets.fills", "operation", "start", "result", "ok").increment();
    }

    /** The filler saved their cells. */
    void saved(UUID actor, String object, UUID fill, long version, FillValidator.Checked checked) {
        audit.record(counts(base("sheets.fill.saved", actor, object, fill), version, checked));
        meters.counter("platform.sheets.fills", "operation", "save", "result", "ok").increment();
        DistributionSummary.builder("platform.sheets.fill.bytes").baseUnit("bytes").register(meters)
                .record(checked.bytes());
    }

    /**
     * A person who may correct a fill-in that was sent changed its cells (decision 11 of the business analysis: no
     * tracking of the change beyond the normal audit record). The size, the counts and the hash say that it changed and
     * how much; the cells are never written down.
     */
    void corrected(UUID actor, String object, UUID fill, long version, int corrections,
            FillValidator.Checked checked) {
        audit.record(counts(base("sheets.fill.corrected", actor, object, fill), version, checked)
                .with("corrections", Integer.toString(corrections)));
        meters.counter("platform.sheets.fills", "operation", "correct", "result", "ok").increment();
    }

    /** The fill-in went to review under a request. {@code actor} is null when the platform sent it. */
    void submitted(UUID actor, String object, UUID fill, int versionNo, UUID requestId, int rows) {
        audit.record(base("sheets.fill.submitted", actor, object, fill).with("version_no", Integer.toString(versionNo))
                .with("request", requestId.toString()).with("rows", Integer.toString(rows)));
        meters.counter("platform.sheets.fills", "operation", "submit", "result", "ok").increment();
    }

    /** A review ended; {@code status} is the status the fill-in now has. */
    void reviewed(UUID actor, String object, UUID fill, String status) {
        audit.record(base("sheets.fill.reviewed", actor, object, fill).with("status", status));
        meters.counter("platform.sheets.fills", "operation", "review", "result", status.toLowerCase(Locale.ROOT))
                .increment();
    }

    /** A request was refused; {@code reason} is one of a few fixed words, never a code that names a place. */
    void refused(String operation, String reason) {
        meters.counter("platform.sheets.fills", "operation", operation, "result", reason).increment();
    }

    private static AuditRecord counts(AuditRecord record, long version, FillValidator.Checked checked) {
        long cells = checked.doc().rows().stream().mapToLong(row -> row.cells().size()).sum();
        return record.with("version", Long.toString(version)).with("bytes", Integer.toString(checked.bytes()))
                .with("hash", SheetGridWriter.hex(checked.hash()))
                .with("rows", Integer.toString(checked.doc().rows().size())).with("cells", Long.toString(cells));
    }

    private AuditRecord base(String type, UUID actor, String object, UUID fill) {
        AuditRecord record = AuditRecord.of(type, AuditOutcome.SUCCESS).inTenant(tenantId())
                .onObject(object, fill.toString());
        return actor == null ? record : record.forUser(actor);
    }

    private TenantId tenantId() {
        return contexts.current().map(TenantContext::tenantId).orElse(null);
    }
}
