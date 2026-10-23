package app.platform.inspection.internal;

import app.platform.security.Ability;
import app.platform.security.Permissions;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sharedkernel.files.RecordFileRefusal;
import app.platform.sharedkernel.files.RecordFileRules;
import app.platform.sheets.FillLifecycle;
import app.platform.sheets.FillStatus;
import app.platform.tenant.TenantContexts;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Who may attach a file to a fill-in or take one off (FF2, ADR-0193). The record store already decides that the member
 * may change the header record and, while a review is pending, that the record is not locked against them; a file
 * inherits the visibility of the fill-in. This adds the rule of the lifecycle, so a file follows the cells:
 * <ul>
 *   <li>a draft or a returned fill-in takes and gives up files only for the person who started it (as with the
 *   cells);</li>
 *   <li>a fill-in that was sent for review or approved takes and gives up files only for a person who may correct it:
 *   the ability to override the lock of an approval, besides the right to change the header that the platform has
 *   already checked. That act is audited by the fill-in, the act and the person, never a file name, and counted;</li>
 *   <li>an archived fill-in is kept as it is: no file is added to it or taken off it.</li>
 * </ul>
 * The rule can only refuse. A fill-in that has no row in the sheets module is refused too: nothing attaches to a header
 * that is not a fill-in.
 */
@Component
class FillFileRules implements RecordFileRules {

    private static final String NOT_FILLER = "Only the person who started a fill-in can add or remove its files.";
    private static final String SENT = "A fill-in that was sent for review can have its files changed only by the "
            + "people who may correct it.";
    private static final String ARCHIVED = "This fill-in is archived, so its files can no longer be changed.";
    private static final String NO_FILL = "This fill-in does not exist.";

    private final FillLifecycle fills;
    private final Permissions permissions;
    private final AuditRecorder audit;
    private final TenantContexts contexts;
    private final MeterRegistry meters;

    FillFileRules(FillLifecycle fills, Permissions permissions, AuditRecorder audit, TenantContexts contexts,
            MeterRegistry meters) {
        this.fills = fills;
        this.permissions = permissions;
        this.audit = audit;
        this.contexts = contexts;
        this.meters = meters;
    }

    @Override
    public String object() {
        return Fills.OBJECT;
    }

    @Override
    public void check(Change change) {
        FillLifecycle.Summary summary = fills.summaries(Fills.OBJECT, List.of(change.recordId()))
                .get(change.recordId());
        String act = change.act().name().toLowerCase(Locale.ROOT);
        if (summary == null) {
            refuse(act, "no_fill");
            throw new RecordFileRefusal(RecordFileRefusal.Kind.CONFLICT, NO_FILL);
        }
        if (summary.archivedAt() != null) {
            refuse(act, "archived");
            throw new RecordFileRefusal(RecordFileRefusal.Kind.CONFLICT, ARCHIVED);
        }
        FillStatus status = summary.status();
        if (status.fillerMayChange()) {
            if (!change.userId().equals(summary.startedBy())) {
                refuse(act, "not_the_filler");
                throw new RecordFileRefusal(RecordFileRefusal.Kind.FORBIDDEN, NOT_FILLER);
            }
            return;
        }
        if (!permissions.has(change.membershipId(), Ability.APPROVALS_OVERRIDE_LOCK)) {
            refuse(act, "sent");
            throw new RecordFileRefusal(RecordFileRefusal.Kind.FORBIDDEN, SENT);
        }
        corrected(change, act, status);
    }

    /** A person who may correct changed the files of a fill-in that was sent: counted, and audited without a name. */
    private void corrected(Change change, String act, FillStatus status) {
        audit.record(AuditRecord.of("inspection.fill.file.corrected", AuditOutcome.SUCCESS).forUser(change.userId())
                .inTenant(contexts.require().tenantId()).onObject(Fills.OBJECT, change.recordId().toString())
                .with("act", act).with("status", status.name()));
        meters.counter("platform.inspection.fill.file", "act", act, "result", "corrected").increment();
    }

    private void refuse(String act, String reason) {
        meters.counter("platform.inspection.fill.file", "act", act, "result", reason).increment();
    }
}
