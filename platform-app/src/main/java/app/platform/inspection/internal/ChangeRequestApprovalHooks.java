package app.platform.inspection.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.BackgroundWriteException;
import app.platform.data.DataRecord;
import app.platform.inspection.internal.ChangeRequestStore.RequestRow;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.approval.ApprovalHooks;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What the approval engine asks of the cluster for a change request (FA6E, ADR-0199). It runs inside the engine's own
 * transaction, after the request's status is saved (so the review record is no longer locked), and must be short.
 *
 * <ul>
 *   <li><strong>When the request ends approved</strong>: the change request is accepted. It is the end of the whole
 *   process, so a process with two steps accepts only when the second has approved (or was skipped because nobody could
 *   be asked); nothing is written between the steps.</li>
 *   <li><strong>When it ends rejected</strong>: the change request is declined, and that is final. The reason the
 *   approver typed stays in the approval's own history, where the people who may read it see it; a hook is handed no
 *   comment (ADR-0187), so it is not copied.</li>
 *   <li><strong>When it is recalled or cancelled</strong>: the change request stays open. It can be sent for review
 *   again from the approval panel of its review record.</li>
 * </ul>
 *
 * <p>The person who asked and the owner of the template are told in the bell (facts only, a stable key). The people
 * who have to act are told by the engine's own task notices. Nothing a request holds is written to a log, an audit
 * record or a metric: they carry identifiers.
 */
@Component
class ChangeRequestApprovalHooks implements ApprovalHooks {

    private final ChangeRequestStore store;
    private final ChangeRequestHeaders headers;
    private final ChangeRequestChanges changes;
    private final ChangeRequestNotices notices;
    private final BackgroundRecords records;

    ChangeRequestApprovalHooks(ChangeRequestStore store, ChangeRequestHeaders headers, ChangeRequestChanges changes,
            ChangeRequestNotices notices, BackgroundRecords records) {
        this.store = store;
        this.headers = headers;
        this.changes = changes;
        this.notices = notices;
        this.records = records;
    }

    @Override
    public String object() {
        return RequestRecords.OBJECT;
    }

    @Override
    public void onEnded(EndContext context) {
        ChangeRequestStatus to = switch (context.outcome()) {
            case APPROVED -> ChangeRequestStatus.ACCEPTED;
            case REJECTED -> ChangeRequestStatus.DECLINED;
            case RECALLED, CANCELLED -> null;
            default -> throw new IllegalStateException("Unreachable: every outcome is handled above");
        };
        if (to == null) {
            return;
        }
        RequestRow row = store.findByHeader(context.recordId()).orElse(null);
        if (row == null || !row.state().canMoveTo(to)) {
            return;
        }
        UUID actor = context.endedBy() != null ? context.endedBy() : ActorId.SYSTEM.value();
        // The status the review found; a request that moved meanwhile (somebody was first) is left as it is.
        if (!store.answer(row.id(), row.state(), to, null, null, actor)) {
            return;
        }
        try {
            headers.status(context.recordId(), to);
        } catch (BackgroundWriteException refused) {
            // The record is the platform's own, so this is a fault and not an answer for the person: the decision is
            // undone and the error tracking sees a failure without a value.
            throw new IllegalStateException("The status of a change request could not follow its review ("
                    + refused.reason().key() + ")");
        }
        String act = to == ChangeRequestStatus.ACCEPTED ? "accepted" : "declined";
        changes.answered(actor, row.templateId(), row.id(), act);
        notices.tell(row.createdBy(), actor, row.templateId(), row.id(), act, "chg:" + row.id() + ":" + act);
        records.find(Templates.OBJECT, row.templateId()).map(DataRecord::ownerId).ifPresent(owner -> notices.tell(
                owner, actor, row.templateId(), row.id(), act, "chg:" + row.id() + ":" + act + ":owner"));
    }
}
