package app.platform.inspection.internal;

import app.platform.inspection.internal.ChangeRequestStore.RequestRow;
import app.platform.security.Ability;
import app.platform.security.Permissions;
import app.platform.sharedkernel.approval.ApprovalDecisions;
import app.platform.sharedkernel.approval.ApprovalTasks;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who is asked to decide a change request now (FA8, ADR-0202): the one question behind "may this person create the
 * revised copy, change it and complete the register". A person may when the request is still open and they either hold
 * an open task of its approval or manage access (the organization administrator). The approval module answers the
 * task; this class only asks. A person with the Approver policy and no task now is not asked, so the rule follows the
 * flow and not a policy.
 */
@Component
class ChangeRequestTasks {

    private final ApprovalDecisions decisions;
    private final ApprovalTasks tasks;
    private final Permissions permissions;
    private final RecordSets sets;

    ChangeRequestTasks(ApprovalDecisions decisions, ApprovalTasks tasks, Permissions permissions, RecordSets sets) {
        this.decisions = decisions;
        this.tasks = tasks;
        this.permissions = permissions;
        this.sets = sets;
    }

    /** The approval request that waits on the request's review record, if there is one. */
    Optional<UUID> pending(RequestRow request) {
        if (request.headerId() == null) {
            return Optional.empty();
        }
        // The review record lives in the change-request object of the request's own set.
        return sets.ofTemplate(request.templateObject())
                .flatMap(set -> decisions.pendingRequest(set.request(), request.headerId()));
    }

    /** Whether the person is asked to decide the request now, or manages access. */
    boolean mayAct(RequestRow request, UUID userId, UUID membershipId) {
        if (request.state() != ChangeRequestStatus.OPEN) {
            return false;
        }
        if (permissions.has(membershipId, Ability.ACCESS_MANAGE)) {
            return true;
        }
        return pending(request).filter(id -> tasks.holdsOpenTask(id, userId)).isPresent();
    }
}
