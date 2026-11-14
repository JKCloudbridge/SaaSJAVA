package app.platform.inspection.internal;

import app.platform.data.DataRecord;
import app.platform.sharedkernel.notice.NoticeKind;
import app.platform.sharedkernel.notice.NoticeRequest;
import app.platform.sharedkernel.notice.Notices;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Tells the person who made a document that it was published (MC1.1, ADR-0259), for every document of every
 * application. A notice holds facts and never words: the kind, the document, a stable event key and the address parts
 * of the page that opens it (the application the document was routed to, when the organization has it, else the one
 * that shows every document). The notification module also queues the mail of the same facts; whether either reaches
 * the person is their own choice in the preferences, decided where each is made or sent. No title, number or value of
 * the document is ever put in a notice, a mail, a log or an audit record.
 *
 * <p>The key is the document and the stored number of the version published, so a repeat of the same publish tells
 * nobody twice and a new Revision tells again. Nobody is told of their own act.
 */
@Component
class DocumentPublishedNotices {

    private final Notices notices;
    private final RecordSets sets;

    DocumentPublishedNotices(Notices notices, RecordSets sets) {
        this.notices = notices;
        this.sets = sets;
    }

    /**
     * Tells the document's creator, unless it is the person who published it or there is nobody to tell.
     *
     * @param set the set of records the document belongs to
     * @param header the document's record, as the platform reads it
     * @param actor the user who published it, who is never told
     * @param versionNo the stored number of the version that is now in use
     */
    void tell(RecordSet set, DataRecord header, UUID actor, int versionNo) {
        UUID recipient = header.ownerId();
        if (recipient == null || recipient.equals(actor)) {
            return;
        }
        String application = sets.applicationFor(set, header.fields().get(Templates.APPLICATION));
        notices.notify(new NoticeRequest(NoticeKind.DOCUMENT_PUBLISHED, recipient, set.template(), header.id(),
                Map.of("event", "published", "application", application, "entry", Templates.ENTRY),
                "doc-published:" + header.id() + ":" + versionNo));
    }
}
