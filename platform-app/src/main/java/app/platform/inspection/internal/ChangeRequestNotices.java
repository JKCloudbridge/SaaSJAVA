package app.platform.inspection.internal;

import app.platform.sharedkernel.notice.NoticeKind;
import app.platform.sharedkernel.notice.NoticeRequest;
import app.platform.sharedkernel.notice.Notices;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Tells people about a change request through the in-app notices (FA5, ADR-0191; FA6E, ADR-0199). A notice holds facts
 * and never words: the kind, the template (the record the notice is about), the request, and a stable event key. The
 * key must be the same on every repeat of the cause, so a retry tells nobody twice. The people who have to act on a
 * request in review are told by the approval engine's own task notices, not here; this tells the template's owner and
 * the person who asked. No mail.
 */
@Component
class ChangeRequestNotices {

    private final Notices notices;
    private final RecordSets sets;

    ChangeRequestNotices(Notices notices, RecordSets sets) {
        this.notices = notices;
        this.sets = sets;
    }

    /**
     * Tells one person, unless it is the person who did it or there is nobody to tell.
     *
     * @param set the set of records the template belongs to; the notice leads to an application that shows it
     * @param recipient the user to tell, or null
     * @param actor the user whose act it was, who is never told
     * @param templateId the template the request is about
     * @param requestId the request, which a bell row opens
     * @param event a stable key: requested, commented, accepted, declined or done
     * @param key the dedupe key
     */
    void tell(RecordSet set, UUID recipient, UUID actor, UUID templateId, UUID requestId, String event, String key) {
        if (recipient == null || recipient.equals(actor)) {
            return;
        }
        notices.notify(new NoticeRequest(NoticeKind.CHANGE_REQUEST, recipient, set.template(), templateId,
                Map.of("event", event, "application", sets.applicationFor(set), "entry", Templates.ENTRY,
                        "tab", RequestRecords.TAB, "request", requestId.toString()), key));
    }
}
