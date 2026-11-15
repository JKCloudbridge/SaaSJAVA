package app.platform.inspection.internal;

import app.platform.data.DataRecord;
import app.platform.sharedkernel.notice.NoticeKind;
import app.platform.sharedkernel.notice.NoticeRequest;
import app.platform.sharedkernel.notice.Notices;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Tells a plant head that a fill-in they filled is below the limit, so an Action plan is needed (MC2B, ADR-0261). The
 * person told is the owner of the header, who filled the form. A notice holds facts and never words: the kind, the
 * fill-in, a stable event key and the address parts of the page that opens it; the notification module also queues the
 * mail of the same facts, and each person's own switches decide whether either reaches them. The score, the limit and
 * the plant are never put in a notice, a mail, a log or an audit record: the words say only "below the limit".
 *
 * <p>The key is the fill-in, so the notice is made once however many approvals follow, and a repeat of the cause tells
 * nobody twice.
 */
@Component
class ActionPlanNotices {

    private final Notices notices;
    private final RecordSets sets;

    ActionPlanNotices(Notices notices, RecordSets sets) {
        this.notices = notices;
        this.sets = sets;
    }

    /**
     * Tells the owner of a fill-in that is below the limit.
     *
     * @param set the set of records the fill-in belongs to
     * @param header the fill-in's header, as the platform reads it
     */
    void tell(RecordSet set, DataRecord header) {
        UUID person = header.ownerId();
        if (person == null) {
            return;
        }
        String application = sets.applicationFor(set, header.fields().get(RecordSets.APPLICATION_FIELD));
        notices.notify(new NoticeRequest(NoticeKind.ACTION_PLAN_NEEDED, person, set.fill(), header.id(),
                Map.of("event", "action-plan", "application", application, "entry", Fills.ENTRY),
                "action-plan:" + header.id()));
    }
}
