package app.platform.inspection.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.BackgroundWriteException;
import app.platform.data.DataRecord;
import app.platform.sharedkernel.approval.ApprovalHookRefusal;
import app.platform.sharedkernel.approval.ApprovalHooks;
import app.platform.sheets.FillLifecycle;
import app.platform.sheets.FillRefusal;
import app.platform.sheets.FillStatus;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What the approval engine asks of the cluster for a fill-in (FF1, ADR-0192). It runs inside the engine's own
 * transaction while the header is held, so everything it does is undone with the decision that asked, and it must be
 * short. It writes nothing through the engine's narrow writer: the only thing a fill-in keeps on its header is the copy
 * of its status, which the cluster writes itself ({@link FillHeaders}) before the request exists and after it has
 * ended, when the header is not locked.
 *
 * <ul>
 *   <li><strong>On submit</strong>: the template the fill-in was made for must not be retired (a draft can be finished
 *   after the template is unpublished or has a newer version, because the fill-in is pinned to its version, but not
 *   after the template is retired); then the cells are checked against that version (every required value is there, a
 *   sheet whose filler adds the rows has a row) and the fill-in goes to review under the request.</li>
 *   <li><strong>When the request ends</strong>: an approval approves the fill-in; a rejection returns it to its filler,
 *   who can change it and send it again; a recall or an administrator's cancellation puts it back as a draft.</li>
 * </ul>
 *
 * <p>The refusal words are fixed; they never repeat what a filler typed. Nothing a fill-in holds is written to a log,
 * an
 * audit record or a metric: the engine and the sheets module audit by identifiers and counts. */
@Component
class FillApprovalHooks implements ApprovalHooks {

    private final BackgroundRecords records;
    private final FillLifecycle fills;
    private final FillHeaders headers;
    private final RecordSets sets;

    FillApprovalHooks(BackgroundRecords records, FillLifecycle fills, FillHeaders headers, RecordSets sets) {
        this.records = records;
        this.fills = fills;
        this.headers = headers;
        this.sets = sets;
    }

    /** The fill-in object of the first set; the hook serves the fill-in object of every set ({@link #objects()}). */
    @Override
    public String object() {
        return sets.fills().get(0);
    }

    @Override
    public java.util.Collection<String> objects() {
        return sets.fills();
    }

    private RecordSet setOf(String object) {
        return sets.ofFill(object).orElseThrow(() -> new ApprovalHookRefusal("fill_missing",
                "This fill-in cannot be found."));
    }

    @Override
    public void onSubmit(SubmitContext context) {
        RecordSet set = setOf(context.object());
        DataRecord header = records.find(set.fill(), context.recordId()).orElseThrow(
                () -> new ApprovalHookRefusal("fill_missing", "This fill-in cannot be found."));
        UUID templateId = Fills.templateOf(header.fields().get(Fills.TEMPLATE_ID));
        DataRecord template = templateId == null ? null : records.find(set.template(), templateId).orElse(null);
        if (template == null || Boolean.TRUE.equals(template.fields().get(Templates.RETIRED))) {
            throw new ApprovalHookRefusal("template_retired",
                    "The template this fill-in was made for is retired, so the fill-in can no longer be sent.");
        }
        try {
            fills.submit(set.fill(), context.recordId(), context.requestId(), context.submitter());
        } catch (FillRefusal refusal) {
            throw new ApprovalHookRefusal("fill_" + refusal.reason().name().toLowerCase(Locale.ROOT),
                    Templates.fit(refusal.getMessage()));
        }
        follow(set, context.recordId(), FillStatus.SUBMITTED);
    }

    @Override
    public void onEnded(EndContext context) {
        FillStatus result = switch (context.outcome()) {
            case APPROVED -> FillStatus.APPROVED;
            case REJECTED -> FillStatus.RETURNED;
            case RECALLED, CANCELLED -> FillStatus.DRAFT;
            default -> throw new IllegalStateException("Unreachable: every outcome is handled above");
        };
        RecordSet set = setOf(context.object());
        if (fills.ended(set.fill(), context.recordId(), context.requestId(), result, context.endedBy())) {
            follow(set, context.recordId(), result);
        }
    }

    /** Keeps the copy of the status on the header in step with the fill-in; a failure undoes the decision. */
    private void follow(RecordSet set, UUID fillId, FillStatus status) {
        try {
            headers.status(set.fill(), fillId, status);
        } catch (BackgroundWriteException refused) {
            // Not a refusal for the person: the header is the platform's own record. The decision is undone and the
            // error tracking sees a failure without a value.
            throw new IllegalStateException("The status of a fill-in could not follow its review ("
                    + refused.reason().key() + ")");
        }
    }
}
