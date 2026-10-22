package app.platform.sheets;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The moves of a version through its lifecycle (FA4, ADR-0188, ADR-0174 section 2), for the module that owns the thing
 * a sheet belongs to. The sheets module knows no business word: it moves versions of the sheet of an owner (an object
 * and a record) and the module that decides when it is allowed (an approval engine hook, a person with the ability to
 * publish) calls it. The database refuses every move the lifecycle lacks, whatever is asked here.
 *
 * <p><strong>No member is behind these calls.</strong> They do not ask who may read or change the owner record
 * ({@code SheetOwners} does that for the endpoints of the sheets): the caller has already decided, and it calls them in
 * its own transaction for the organization of the thread's tenant context. They are reachable from the cluster
 * module only (an architecture test names it). Every move audits by the owner, the version number and sizes, never a
 * cell.
 *
 * <p>One sheet at a time is changed: each call takes the same lock as starting a version does, so two moves of one
 * sheet are decided one after the other. The moves of different sheets never wait for each other.
 */
public interface SheetLifecycle {

    /**
     * The draft goes to review: it becomes {@code IN_REVIEW} under the request and its grid is fixed (except for the
     * counted edit of an approver).
     *
     * @param ownerObject the owner's object
     * @param ownerId the owner record
     * @param requestId the approval request that will review it
     * @param actor the person who sent it
     * @return the number of the version
     * @throws SheetMoveRefusal when there is no draft, it is empty or not valid, or another version is in review
     */
    int submit(String ownerObject, UUID ownerId, UUID requestId, UUID actor);

    /**
     * The review of a request ended: the version it reviewed becomes {@code APPROVED}, {@code REJECTED} or
     * {@code WITHDRAWN}. Nothing happens when the request reviewed no version of this owner (it began before the
     * sheet).
     *
     * @param result {@link SheetStatus#APPROVED}, {@link SheetStatus#REJECTED} or {@link SheetStatus#WITHDRAWN}
     * @param actor the person whose act ended it, or null when the platform did
     * @return whether a version was moved
     */
    boolean reviewed(String ownerObject, UUID ownerId, UUID requestId, SheetStatus result, UUID actor);

    /**
     * An approved version becomes the one in use; the version that was in use becomes {@code SUPERSEDED}, in the same
     * transaction.
     *
     * @return the number of the version that was replaced, or 0 when none was in use
     * @throws SheetMoveRefusal {@code NOT_APPROVED} when that version is not an approved one
     */
    int publish(String ownerObject, UUID ownerId, int versionNo, UUID actor);

    /**
     * The version in use stops being so and is {@code APPROVED} again.
     *
     * @return the number of the version
     * @throws SheetMoveRefusal {@code NOTHING_ACTIVE} when none is in use
     */
    int unpublish(String ownerObject, UUID ownerId, UUID actor);

    /**
     * The version in use ends as {@code RETIRED}. Nothing is deleted.
     *
     * @return the number of the version, or 0 when none was in use
     */
    int retire(String ownerObject, UUID ownerId, UUID actor);

    /**
     * What the versions of these owners add up to, without reading a grid: one statement for all of them.
     *
     * @return a summary for each owner that has a version, by owner; an owner without one is not in the map
     */
    Map<UUID, Summary> summaries(String ownerObject, Collection<UUID> ownerIds);

    /**
     * The state of one sheet; a number is 0 when no version has that status.
     *
     * @param active the version in use
     * @param approved the latest approved version that is not in use
     * @param inReview the version being reviewed
     * @param draft the draft
     * @param retired the latest retired version
     * @param highest the highest number
     * @param versions how many versions there are
     */
    record Summary(int active, int approved, int inReview, int draft, int retired, int highest, int versions) {
    }
}
