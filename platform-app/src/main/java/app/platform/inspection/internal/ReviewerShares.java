package app.platform.inspection.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.data.RecordShares;
import app.platform.security.Grantee;
import app.platform.security.GroupMembers;
import app.platform.sharedkernel.approval.PublicationReviewers;
import app.platform.sharedkernel.directory.ReferenceLabels;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Lets the reviewers chosen for a plant read the one fill-in they are asked to review (2026-11-17, a note on
 * ADR-0260). A fill-in is private: its filler owns it and the plant's plant heads read it. The people chosen to review
 * it hold no right to read every fill-in (that would show them every plant), so without this they would hold a task
 * for a record they cannot open: no link in their inbox, no sheet, no Submit. When the fill-in is sent, and when the
 * reviewers of its plant change while it waits, the header is shared read only with each reviewer chosen then, a
 * person or a group. The share needs the reader to have the right to read fill-ins at all (the shipped Reader policy
 * gives it); it is never taken back, so a person once asked to review a fill-in can still read it.
 *
 * <p>The share is made only for a grantee the record store accepts (an active member who does not own the fill-in, an
 * existing group), so it never throws through a transaction it is part of. Nothing is logged: who reviews a plant is
 * the organization's.
 */
@Component
class ReviewerShares {

    private static final String USER_OBJECT = "User";

    private final PlantReviewers reviewers;
    private final RecordShares shares;
    private final BackgroundRecords records;
    private final GroupMembers groups;
    private final List<ReferenceLabels> labels;

    ReviewerShares(PlantReviewers reviewers, RecordShares shares, BackgroundRecords records, GroupMembers groups,
            List<ReferenceLabels> labels) {
        this.reviewers = reviewers;
        this.shares = shares;
        this.records = records;
        this.groups = groups;
        this.labels = labels;
    }

    /**
     * Shares the fill-in read only with the reviewers chosen for it now.
     *
     * @param fillObject the fill-in object
     * @param fillId the fill-in (a monthly fill-in or an Action plan)
     * @param actor the person on whose behalf it is done, for the audit record of the share
     * @return how many shares were made
     */
    int shareWithReviewers(String fillObject, UUID fillId, UUID actor) {
        DataRecord header = records.find(fillObject, fillId).orElse(null);
        if (header == null) {
            return 0;
        }
        PublicationReviewers.Reviewers chosen = reviewers.reviewersOf(fillObject, fillId);
        Map<UUID, String> active = chosen.userIds().isEmpty() ? Map.of()
                : users().map(one -> one.labels(chosen.userIds())).orElse(Map.of());
        int made = 0;
        for (UUID user : chosen.userIds()) {
            if (active.containsKey(user) && !user.equals(header.ownerId())) {
                made += grant(fillObject, fillId, new Grantee(Grantee.Kind.USER, user), actor);
            }
        }
        for (UUID group : chosen.groupIds()) {
            if (groups.exists(group)) {
                made += grant(fillObject, fillId, new Grantee(Grantee.Kind.GROUP, group), actor);
            }
        }
        return made;
    }

    private int grant(String fillObject, UUID fillId, Grantee grantee, UUID actor) {
        return shares.grantRead(fillObject, fillId, grantee, actor) == RecordShares.Granted.CREATED ? 1 : 0;
    }

    private Optional<ReferenceLabels> users() {
        return labels.stream().filter(one -> USER_OBJECT.equals(one.object())).findFirst();
    }
}
