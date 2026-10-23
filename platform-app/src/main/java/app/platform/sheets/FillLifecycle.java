package app.platform.sheets;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The moves of a fill-in through its lifecycle (FF1, ADR-0192), for the module that owns the header record of the
 * fill-in. A fill-in is what a person types into a sheet that was published: its header is a record of an object of the
 * caller's own (the fill object and id), its cells are kept here, and it is pinned for ever to the version of the sheet
 * it was started on. The sheets module knows no business word: the module that decides when a move is allowed (the
 * start of a fill-in, an approval engine hook) calls it. The database refuses every move the lifecycle lacks, whatever
 * is asked here.
 *
 * <p><strong>No member is behind these calls.</strong> They do not ask who may read or change the header record
 * (the endpoints of the sheets do that): the caller has already decided, and calls them in its own transaction for the
 * organization of the thread's tenant context. They are reachable from the cluster module only (an architecture test
 * names it). Every move audits by the header, the version number and sizes, never a cell.
 */
public interface FillLifecycle {

    /**
     * Starts a fill-in: one empty cells document, pinned to a version of the sheet. The database refuses a version that
     * is not in use.
     *
     * @param fillObject the object of the header record
     * @param fillId the header record
     * @param ownerObject the object of the sheet's owner
     * @param ownerId the record the sheet belongs to
     * @param versionNo the version in use that the fill-in is made on
     * @param actor the person who starts it
     * @throws FillRefusal {@code NOT_IN_USE} when that version is not the one in use, {@code ALREADY_STARTED}
     */
    void start(String fillObject, UUID fillId, String ownerObject, UUID ownerId, int versionNo, UUID actor);

    /**
     * The fill-in goes to review under the request: the cells are checked against the version it is pinned to
     * (required values, at least one row for a sheet whose filler adds the rows) and the status becomes
     * {@code SUBMITTED}.
     *
     * @param requestId the approval request that will review it
     * @param actor the person who sent it, or null when the platform did
     * @throws FillRefusal {@code NO_FILL}, {@code NOT_FILLABLE}, {@code NO_ROWS}, {@code INCOMPLETE} (with the place),
     *         {@code INVALID}, {@code VERSION_RETIRED}
     */
    void submit(String fillObject, UUID fillId, UUID requestId, UUID actor);

    /**
     * The review of a request ended: the fill-in it reviewed becomes {@code APPROVED}, {@code RETURNED} (a rejection)
     * or {@code DRAFT} (the filler took it back). Nothing happens when the request reviewed no fill-in of this header.
     *
     * @param result one of those three
     * @param actor the person whose act ended it, or null when the platform did
     * @return whether a fill-in was moved
     */
    boolean ended(String fillObject, UUID fillId, UUID requestId, FillStatus result, UUID actor);

    /** What a fill-in is pinned to, or empty when the header has no fill-in row. Reads no cells. */
    Optional<Pin> pinOf(String fillObject, UUID fillId);

    /**
     * What a fill-in is pinned to.
     *
     * @param ownerObject the object of the sheet's owner
     * @param ownerId the record the sheet belongs to
     * @param versionNo the version the fill-in was made on
     */
    record Pin(String ownerObject, UUID ownerId, int versionNo) {
    }

    /**
     * The facts of these fill-ins, without reading a cells document: one statement for all of them.
     *
     * @return a summary for each header that has a fill-in, by header; one without is not in the map
     */
    Map<UUID, Summary> summaries(String fillObject, Collection<UUID> fillIds);

    /**
     * The facts of one fill-in.
     *
     * @param versionNo the version of the sheet it is pinned to
     * @param status its status
     * @param rowCount how many rows it has
     * @param cellsBytes the stored size of its cells document
     * @param corrections how many times the cells were corrected after submit
     * @param createdAt when it was started
     * @param updatedAt when it last changed
     * @param submittedAt when it was last sent for review, or null
     * @param decidedAt when its last review ended, or null
     */
    record Summary(int versionNo, FillStatus status, int rowCount, long cellsBytes, int corrections, Instant createdAt,
            Instant updatedAt, Instant submittedAt, Instant decidedAt) {
    }
}
