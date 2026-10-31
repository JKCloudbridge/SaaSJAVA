package app.platform.inspection.internal;

import app.platform.inspection.internal.ChangeRequestStore.RequestRow;
import app.platform.security.Decisions;
import app.platform.security.ObjectAction;
import app.platform.security.RecordRef;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who may see and answer the change requests of a template, asked in one place (FA5, ADR-0191). The rule is live, not a
 * share: nothing is written when a request is made, so there is nothing to go stale when a template changes hands or a
 * person's access ends.
 *
 * <ul>
 *   <li>A person reaches the requests of a template only while they can read the template (the record store decides,
 *   as it does for every record): a person who can no longer read it, an unknown identifier and the platform host all
 *   get "not found".</li>
 *   <li>A person sees <strong>their own requests</strong>, and sees <strong>every request</strong> when they may change
 *   the template (its owner, and the people who hold <em>modify all</em> on templates, which the Approver policy
 *   gives).
 *   Every other reader sees none of them.</li>
 *   <li>Answering (accepting, declining, turning into a version, reopening) is for the people who may change the
 *   template. A request grants nothing and changes nothing of the template or its versions.</li>
 * </ul>
 */
@Component
class ChangeRequestAccess {

    /**
     * What a member may do about the requests of one template.
     *
     * @param userId the user
     * @param membershipId the membership of that user
     * @param templateId the template
     * @param canReview whether the member may change the template, and so answer requests and see all of them
     * @param set the set of records the template belongs to
     */
    record Reach(UUID userId, UUID membershipId, UUID templateId, boolean canReview, RecordSet set) {
    }

    private static final String NO_REQUEST = "This change request does not exist.";

    private final TemplateAccess access;
    private final Decisions decisions;
    private final ChangeRequestStore store;

    ChangeRequestAccess(TemplateAccess access, Decisions decisions, ChangeRequestStore store) {
        this.access = access;
        this.decisions = decisions;
        this.store = store;
    }

    /**
     * The request, when the member may see it: the same answer for a request that does not exist and one they may not
     * see.
     *
     * @throws app.platformapi.ApiException {@code NOT_FOUND}
     */
    RequestRow request(Reach reach, UUID requestId) {
        RequestRow row = store.find(reach.templateId(), requestId).orElse(null);
        if (row == null || !sees(reach, row)) {
            throw app.platformapi.ApiException.notFound(NO_REQUEST);
        }
        return row;
    }

    /** The member's reach, for a template they can read; "not found" otherwise. */
    Reach reach(RecordSet set, UUID templateId) {
        TemplateAccess.Caller caller = access.visible(set, templateId);
        return new Reach(caller.userId(), caller.membershipId(), templateId,
                canReview(set, caller.membershipId(), templateId), set);
    }

    /**
     * Whether the member may change the template, so answer its requests and see all of them. The same question as
     * {@link #reach} asks, without reading the template again: for a list that judges many templates in one go.
     */
    boolean canReview(RecordSet set, UUID membershipId, UUID templateId) {
        return decisions.can(membershipId, set.template(), ObjectAction.UPDATE, new RecordRef(templateId)).allowed();
    }

    /** Whether the member may change any template of the set at all (the object right), a cheap first question. */
    boolean mayReviewSome(RecordSet set, UUID membershipId) {
        return decisions.can(membershipId, set.template(), ObjectAction.UPDATE).allowed();
    }

    /** The member behind the request, for a list that is not about one template; "not found" on the platform host. */
    TemplateAccess.Caller member() {
        return access.member();
    }

    /** Whether the member may see the request: their own, or any when they may review. */
    boolean sees(Reach reach, RequestRow request) {
        return reach.canReview() || request.createdBy().equals(reach.userId());
    }
}
