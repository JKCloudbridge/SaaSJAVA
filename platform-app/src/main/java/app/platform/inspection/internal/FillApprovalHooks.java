package app.platform.inspection.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.BackgroundWriteException;
import app.platform.data.DataRecord;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.approval.ApprovalHookRefusal;
import app.platform.sharedkernel.approval.ApprovalHookWriteException;
import app.platform.sharedkernel.approval.ApprovalHookWriter;
import app.platform.sharedkernel.approval.ApprovalHooks;
import app.platform.sheets.FillLifecycle;
import app.platform.sheets.FillRefusal;
import app.platform.sheets.FillStatus;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *   sheet whose filler adds the rows has a row) and the fill-in goes to review under the request; the reviewers chosen
 *   for its plant are given a read share of it ({@link ReviewerShares}).</li>
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
    private final FillScores scores;
    private final ActionPlanNotices actionPlanNotices;
    private final ReviewerShares reviewerShares;
    private final FillPeriods periods;

    FillApprovalHooks(BackgroundRecords records, FillLifecycle fills, FillHeaders headers, RecordSets sets,
            FillScores scores, ActionPlanNotices actionPlanNotices, ReviewerShares reviewerShares,
            FillPeriods periods) {
        this.periods = periods;
        this.reviewerShares = reviewerShares;
        this.scores = scores;
        this.actionPlanNotices = actionPlanNotices;
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
        stampPlanSent(set, header);
        // The reviewers chosen for the plant may open the fill-in they are asked about (2026-11-17); a fill-in with
        // no plant has none and nothing is shared.
        reviewerShares.shareWithReviewers(set.fill(), context.recordId(),
                context.submitter() == null ? ActorId.SYSTEM.value() : context.submitter());
    }

    /** The fields the hook may set on a header: what the sheet's score means (MC2B, ADR-0261). */
    @Override
    public List<String> writes() {
        return List.of(Fills.SCORE, Fills.RESULT, Fills.SCORED_ON);
    }

    /**
     * At every approval (MC2B, ADR-0261): the score is calculated from the cells as they are now and written on the
     * header with its result, so a reviewer's edit is always seen and the number a person reads is the one the
     * decision was taken on. A sheet that marks no score is not touched. A score that cannot be calculated is never a
     * pass: the approval is refused in fixed words and nothing is written. A fill-in below the limit tells its filler
     * once that an Action plan is needed. Since the one-step review (ADR-0261, note of 2026-11-17) the reviewer's
     * Submit finishes the fill-in whatever its score: the Action plan comes after it and is reviewed on its own, and
     * the dashboard shows the plants that still owe one.
     */
    @Override
    public void beforeApprove(DecisionContext context, ApprovalHookWriter writer) {
        RecordSet set = setOf(context.object());
        DataRecord header = records.find(set.fill(), context.recordId()).orElseThrow(
                () -> new ApprovalHookRefusal("fill_missing", "This fill-in cannot be found."));
        FillScores.Judgement judgement = scores.judge(set.fill(), header);
        if (judgement.state() == FillScores.State.NO_SCORE) {
            return;
        }
        if (judgement.state() == FillScores.State.UNREADABLE) {
            throw new ApprovalHookRefusal("score_not_calculated", "The score of this fill-in cannot be calculated, "
                    + "so it cannot be approved yet.");
        }
        Map<String, String> changes = scores.changes(header, judgement);
        if (isBlank(header.fields().get(Fills.SCORED_ON))) {
            // The first Submit stamps the day, from the platform's clock; a correction later never moves it (ADR-0266).
            changes.put(Fills.SCORED_ON, periods.today());
        }
        if (!changes.isEmpty()) {
            try {
                writer.set(changes);
            } catch (ApprovalHookWriteException refused) {
                throw new ApprovalHookRefusal("score_not_written", "The score of this fill-in could not be saved.");
            }
        }
        if (Fills.ACTION_PLAN.equals(judgement.result())) {
            actionPlanNotices.tell(set, header);
        }
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

    /**
     * The first time a plant head sends an Action plan, its header gets the day (ADR-0266). A plan sent again after a
     * return keeps its first day; a fill-in that is not an Action plan is not stamped.
     */
    private void stampPlanSent(RecordSet set, DataRecord header) {
        if (isBlank(header.fields().get(Fills.PARENT_FILL)) || !isBlank(header.fields().get(Fills.PLAN_SENT_ON))) {
            return;
        }
        try {
            headers.planSent(set.fill(), header.id(), periods.today());
        } catch (BackgroundWriteException refused) {
            throw new IllegalStateException("The day an Action plan was sent could not be written ("
                    + refused.reason().key() + ")");
        }
    }

    private static boolean isBlank(Object stored) {
        return !(stored instanceof String text) || text.isBlank();
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
