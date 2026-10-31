package app.platform.inspection.internal;

import app.platform.data.BackgroundWrites;
import app.platform.data.BackgroundWrites.Assignment;
import app.platform.data.BackgroundWrites.Source;
import app.platform.data.BackgroundWrites.Value;
import app.platform.data.DataRecord;
import app.platform.data.RecordShares;
import app.platform.security.Grantee;
import app.platform.sharedkernel.directory.ReferenceDirectory;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The small records that give change requests to the approval engine, written as the platform (FA6E, ADR-0199): the
 * cluster's third and last place that takes the platform's door to records, after the retired mark of a template and
 * the header of a fill-in (ADR-0188, ADR-0192). A member never creates or changes one: every field is one only the
 * platform writes and no shipped policy gives a create right or a field to edit. The door is entered at two moments,
 * and only here: when a request is raised (the record is made with the person who asks as its owner) and when the
 * request's status moves (the copy the lists filter on). Both are acts the cluster has already decided.
 *
 * <p>The record is also shared for reading with the owner of the template, so the person who has to turn an accepted
 * request into a version can follow its review. Nothing a record holds is written to a log, an audit record or a
 * metric.
 */
@Component
class ChangeRequestHeaders {

    private final BackgroundWrites writes;
    private final RecordShares shares;
    private final List<ReferenceDirectory> directories;

    ChangeRequestHeaders(BackgroundWrites writes, RecordShares shares, List<ReferenceDirectory> directories) {
        this.writes = writes;
        this.shares = shares;
        this.directories = directories;
    }

    /**
     * Makes the record of a new request.
     *
     * @param set the set of records the template belongs to; the record is made in its change-request object
     * @param requester the user who asks; the owner of the record
     * @param templateOwner the owner of the template, who is given the right to read the record, or null
     * @param templateId the template the request is about
     * @param name the name shown for it in the approval inbox: that of the template at the time
     */
    DataRecord create(RecordSet set, UUID requester, UUID templateOwner, UUID templateId, String name) {
        DataRecord made = writes.create(Source.solution("request.raise", templateId), set.request(),
                requester, List.of(
                        new Assignment(RequestRecords.NAME, new Value.Literal(name)),
                        new Assignment(RequestRecords.TEMPLATE_ID, new Value.Literal(templateId.toString())),
                        new Assignment(RequestRecords.STATUS, new Value.Literal(ChangeRequestStatus.OPEN.name()))));
        // A refusal of the share would end the whole request, so a template owner who has left is not asked for.
        if (templateOwner != null && !templateOwner.equals(requester) && isMember(templateOwner)) {
            shares.grantRead(set.request(), made.id(), new Grantee(Grantee.Kind.USER, templateOwner),
                    requester);
        }
        return made;
    }

    private boolean isMember(UUID user) {
        return directories.stream().filter(one -> "User".equals(one.object())).findFirst()
                .map(one -> one.exists(user)).orElse(false);
    }

    /** Follows a move of the request on its record. */
    void status(RecordSet set, UUID headerId, ChangeRequestStatus status) {
        writes.update(Source.solution("request.status", headerId), set.request(), headerId,
                List.of(new Assignment(RequestRecords.STATUS, new Value.Literal(status.name()))));
    }
}
