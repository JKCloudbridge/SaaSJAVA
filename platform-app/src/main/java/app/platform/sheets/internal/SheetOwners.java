package app.platform.sheets.internal;

import app.platform.data.RecordTitles;
import app.platform.security.Ability;
import app.platform.security.Decisions;
import app.platform.security.ObjectAction;
import app.platform.security.Permissions;
import app.platform.security.RecordRef;
import app.platform.sharedkernel.approval.ApprovalTasks;
import app.platform.sharedkernel.solution.SheetOwnerDeclarations;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The one place that decides who may reach a sheet (FA3, FA4, ADR-0186, ADR-0189). A sheet has no permission of its
 * own: it belongs to a record, and what a member may do with the sheet is what the decision API says they may do with
 * that record, read for reading and update for changing. There is never a second Java rule for who may see a record
 * (ADR-0115), and a share of the record (to a person, a group or a role) reaches the sheet through the same answer.
 *
 * <p>What it decides, in this order:
 * <ol>
 *   <li>Who asks: the member behind the request, from the tenant context (the host and the token, never the request).
 *   Where no member is involved, such as the platform host, the answer is "not found".</li>
 *   <li>Whether the object may own a sheet at all: only the objects a shipped solution declares. Anything else is
 *   "not found", like an object that does not exist.</li>
 *   <li>Whether the member may read the record. A member who may not, a record that does not exist and an object that
 *   cannot own a sheet all get <strong>the same answer</strong>, so nobody learns what exists.</li>
 *   <li>For a change: whether the member may update the record. A member who may read but not change gets "forbidden".
 *   </li>
 * </ol>
 *
 * <p><strong>The two questions of the approval sprints, asked here and nowhere else.</strong> (1) Whether a member may
 * correct the grid of a version that is in review: only with the ability to override the lock of an approval, besides
 * the right to change the record ({@link #mayCorrectInReview}); the creator who submitted it has neither by that act.
 * (2) Whether a person asked to approve may read the version they are asked about although they may not read the record
 * ({@link #holdsOpenTask}): an approval grants no access to the record (ADR-0146), so this opens that one version and
 * nothing else of the record.
 */
@Component
class SheetOwners {

    private static final String NO_SHEET = "This sheet does not exist.";
    private static final Pattern OBJECT = Pattern.compile("[A-Z][A-Za-z0-9_]{0,59}");

    /**
     * Who is asking and for which record.
     *
     * @param userId the user
     * @param membershipId the membership of that user in the organization
     * @param object the owner object
     * @param id the owner record
     * @param canChange whether the member may change the record, and so its sheet
     */
    record Reach(UUID userId, UUID membershipId, String object, UUID id, boolean canChange) {
    }

    /**
     * The member behind a request, for a sheet whose owner object may own one.
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
    private final ObjectProvider<SheetOwnerDeclarations> declarations;

    SheetOwners(Decisions decisions, Permissions permissions, RecordTitles records, TenantContexts contexts,
            ApprovalTasks tasks, ObjectProvider<SheetOwnerDeclarations> declarations) {
        this.decisions = decisions;
        this.permissions = permissions;
        this.records = records;
        this.contexts = contexts;
        this.tasks = tasks;
        this.declarations = declarations;
    }

    /** The member behind the request, for an object that may own a sheet; "not found" otherwise. */
    Caller caller(String ownerObject) {
        TenantContext context = contexts.current().filter(current -> current.membershipId() != null)
                .orElseThrow(() -> ApiException.notFound(NO_SHEET));
        if (ownerObject == null || !OBJECT.matcher(ownerObject).matches() || !owns(ownerObject)) {
            throw ApiException.notFound(NO_SHEET);
        }
        return new Caller(context.userId(), context.membershipId());
    }

    /** The member's reach to the sheet of the record. */
    Reach reach(String ownerObject, UUID ownerId) {
        return reachIfReadable(ownerObject, ownerId).orElseThrow(() -> ApiException.notFound(NO_SHEET));
    }

    /** The member's reach to the sheet of the record, or empty when they may not read the record. */
    Optional<Reach> reachIfReadable(String ownerObject, UUID ownerId) {
        Caller caller = caller(ownerObject);
        // The record must exist and be one the member reads, by the stages a list passes. The decision API alone is not
        // enough: a member who may view every record of the object is allowed any identifier, including one that names
        // no record, and a sheet must never be attached to nothing.
        if (!records.titlesOf(ownerObject, List.of(ownerId)).containsKey(ownerId)) {
            return Optional.empty();
        }
        RecordRef record = new RecordRef(ownerId);
        if (!decisions.can(caller.membershipId(), ownerObject, ObjectAction.READ, record).allowed()) {
            return Optional.empty();
        }
        boolean change = decisions.can(caller.membershipId(), ownerObject, ObjectAction.UPDATE, record).allowed();
        return Optional.of(new Reach(caller.userId(), caller.membershipId(), ownerObject, ownerId, change));
    }

    /** The member's reach to the sheet of the record, for a change: refused with "forbidden" when they may not. */
    Reach reachForChange(String ownerObject, UUID ownerId) {
        Reach reach = reach(ownerObject, ownerId);
        if (!reach.canChange()) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return reach;
    }

    /**
     * Whether the member may correct the grid of a version that is in review: they may change the record (the caller
     * has asked {@link #reachForChange}) and hold the ability to override the lock of an approval (ADR-0174 section 4).
     * Both are needed because a pending request locks the record for everyone, and the ability is what passes the lock.
     */
    boolean mayCorrectInReview(Reach reach) {
        return reach.canChange() && permissions.has(reach.membershipId(), Ability.APPROVALS_OVERRIDE_LOCK);
    }

    /** Whether the person holds an open task of the request: a yes or no, nothing else is learned. */
    boolean holdsOpenTask(UUID requestId, UUID userId) {
        return requestId != null && userId != null && tasks.holdsOpenTask(requestId, userId);
    }

    private boolean owns(String object) {
        SheetOwnerDeclarations declared = declarations.getIfAvailable();
        return declared != null && declared.owners().contains(object);
    }
}
