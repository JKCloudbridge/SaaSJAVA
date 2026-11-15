package app.platform.inspection.internal;

import app.platform.data.DataRecord;
import app.platform.security.GroupMembers;
import app.platform.sharedkernel.directory.ReferenceLabels;
import app.platform.sharedkernel.notice.NoticeKind;
import app.platform.sharedkernel.notice.NoticeRequest;
import app.platform.sharedkernel.notice.Notices;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Tells the people of a plant that a document was made available to it (MC2A, ADR-0260): the members of the plant's
 * group of plant heads, nested groups included, who are active members. A notice holds facts and never words: the kind,
 * the document, a stable event key and the address parts of the page that opens it. The notification module also
 * queues the mail of the same facts; whether either reaches a person is their own choice in the preferences, decided
 * where each is made or sent. No title, number or plant name is ever put in a notice, a mail, a log or an audit
 * record.
 *
 * <p>The key is the plant's publication row, so a repeat of the same act tells nobody twice and a plant that is taken
 * back and given again tells again. Nobody is told of their own act.
 */
@Component
class PlantPublishedNotices {

    private static final String USER_OBJECT = "User";

    private final Notices notices;
    private final GroupMembers groups;
    private final List<ReferenceLabels> labels;
    private final RecordSets sets;

    PlantPublishedNotices(Notices notices, GroupMembers groups, List<ReferenceLabels> labels, RecordSets sets) {
        this.notices = notices;
        this.groups = groups;
        this.labels = labels;
        this.sets = sets;
    }

    /**
     * Tells the active members of a plant's group.
     *
     * @param plantSet the set of the application the document was routed to
     * @param header the document's record, as the platform reads it
     * @param groupId the plant's group of plant heads
     * @param publicationId the row that made the document available to the plant (the key of the notice)
     * @param actor the user who published, who is never told
     * @return how many people were told
     */
    int tell(RecordSet plantSet, DataRecord header, UUID groupId, UUID publicationId, UUID actor) {
        Set<UUID> people = groups.usersOf(groupId);
        if (people.isEmpty()) {
            return 0;
        }
        Set<UUID> active = labels.stream().filter(one -> USER_OBJECT.equals(one.object())).findFirst()
                .map(one -> one.labels(people).keySet()).orElse(Set.of());
        String application = sets.applicationFor(plantSet, header.fields().get(Templates.APPLICATION));
        int told = 0;
        for (UUID person : active) {
            if (person.equals(actor)) {
                continue;
            }
            notices.notify(new NoticeRequest(NoticeKind.DOCUMENT_AT_PLANT, person, plantSet.template(), header.id(),
                    Map.of("event", "at-plant", "application", application, "entry", Templates.ENTRY),
                    "doc-at-plant:" + publicationId));
            told++;
        }
        return told;
    }
}
