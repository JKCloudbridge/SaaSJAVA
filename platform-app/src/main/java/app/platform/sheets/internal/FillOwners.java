package app.platform.sheets.internal;

import app.platform.data.RecordTitles;
import app.platform.security.Ability;
import app.platform.security.Decisions;
import app.platform.security.ObjectAction;
import app.platform.security.Permissions;
import app.platform.security.RecordRef;
import app.platform.sharedkernel.approval.ApprovalTasks;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The one place that decides who may reach the cells of a fill-in (FF1, ADR-0192). A fill-in has no permission of its
 * own: its cells belong to a header record, and what a member may do with the cells is what the decision API says they
 * may do with that record, read for reading and update for changing. There is never a second Java rule for who may see
 * a record (ADR-0115): the private default of the header object, the owner, the people who may view every record and a
 * share all reach the cells through the same answer.
 *
 * <p>What it decides, in this order:
 * <ol>
 *   <li>Who asks: the member behind the request, from the tenant context. Where no member is involved, such as the
 *   platform host, the answer is "not found".</li>
 *   <li>Whether the member may read the header record. A member who may not, a header that does not exist and an object
 *   with no fill-ins all get <strong>the same answer</strong>, so nobody learns what exists.</li>
 *   <li>For a change: whether the member may update the header record. A member who may read but not change gets
 *   "forbidden".</li>
 * </ol>
 *
 * <p><strong>The two questions of the approval sprints, asked here and nowhere else.</strong> (1) Whether a member may
 * correct the cells of a fill-in that was already sent: only with the ability to override the lock of an approval,
 * besides the right to change the header ({@link #mayCorrect}); the filler who sent it has neither by that act, so the
 * people who may correct are the people who may edit after submit (decision 11 of the business analysis). (2) Whether a
 * person asked to review may read the fill-in they are asked about although they may not read the header
 * ({@link #holdsOpenTask}): an approval grants no access to the record (ADR-0146), so this opens that one fill-in and
 * nothing else of the record.
 */
@Component
class FillOwners {

    private static final String NO_FILL = "This fill-in does not exist.";
    private static final Pattern OBJECT = Pattern.compile("[A-Z][A-Za-z0-9_]{0,59}");

    /**
     * Who is asking and for which header.
     *
     * @param userId the user
     * @param membershipId the membership of that user in the organization
     * @param object the object of the header
     * @param id the header record
     * @param canChange whether the member may change the header record, and so the cells of a draft
     */
    record Reach(UUID userId, UUID membershipId, String object, UUID id, boolean canChange) {
    }

    /**
     * The member behind a request.
     *
     * @param userId the user
     * @param membershipId the membership of that user in the organization
     */
    record Caller(UUID userId, UUID membershipId) {
    }

    private final Decisions decisions;
    private final Permissions permissions;
    private final RecordTitles records;
    private final TenantContexts contexts;
    private final ApprovalTasks tasks;

    FillOwners(Decisions decisions, Permissions permissions, RecordTitles records, TenantContexts contexts,
            ApprovalTasks tasks) {
        this.decisions = decisions;
        this.permissions = permissions;
        this.records = records;
        this.contexts = contexts;
        this.tasks = tasks;
    }

    /** The member behind the request; "not found" for an object name that cannot be one and on the platform host. */
    Caller caller(String fillObject) {
        TenantContext context = contexts.current().filter(current -> current.membershipId() != null)
                .orElseThrow(() -> ApiException.notFound(NO_FILL));
        if (fillObject == null || !OBJECT.matcher(fillObject).matches()) {
            throw ApiException.notFound(NO_FILL);
        }
        return new Caller(context.userId(), context.membershipId());
    }

    /** The member's reach to the cells of the header, or "not found" when they may not read it. */
    Reach reach(String fillObject, UUID fillId) {
        return reachIfReadable(fillObject, fillId).orElseThrow(() -> ApiException.notFound(NO_FILL));
    }

    /** The member's reach to the cells of the header, or empty when they may not read it. */
    Optional<Reach> reachIfReadable(String fillObject, UUID fillId) {
        Caller caller = caller(fillObject);
        // The record must exist and be one the member reads, by the stages a list passes. The decision API alone is not
        // enough: a member who may view every record of the object is allowed any identifier, including one that names
        // no record.
        if (fillId == null || !records.titlesOf(fillObject, List.of(fillId)).containsKey(fillId)) {
            return Optional.empty();
        }
        RecordRef record = new RecordRef(fillId);
        if (!decisions.can(caller.membershipId(), fillObject, ObjectAction.READ, record).allowed()) {
            return Optional.empty();
        }
        boolean change = decisions.can(caller.membershipId(), fillObject, ObjectAction.UPDATE, record).allowed();
        return Optional.of(new Reach(caller.userId(), caller.membershipId(), fillObject, fillId, change));
    }

    /** The member's reach to the cells of the header, for a change: "forbidden" when they may read but not change. */
    Reach reachForChange(String fillObject, UUID fillId) {
        Reach reach = reach(fillObject, fillId);
        if (!reach.canChange()) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return reach;
    }

    /**
     * Whether the member may correct the cells of a fill-in that was already sent: they may change the header (the
     * caller has asked {@link #reachForChange}) and hold the ability to override the lock of an approval. Both are
     * needed because a pending request locks the record for everyone, and the ability is what passes the lock.
     */
    boolean mayCorrect(Reach reach) {
        return reach.canChange() && permissions.has(reach.membershipId(), Ability.APPROVALS_OVERRIDE_LOCK);
    }

    /** Whether the person holds an open task of the request: a yes or no, nothing else is learned. */
    boolean holdsOpenTask(UUID requestId, UUID userId) {
        return requestId != null && userId != null && tasks.holdsOpenTask(requestId, userId);
    }
}
