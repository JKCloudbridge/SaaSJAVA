package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * The facts about one fill-in without its cells (Functional track FF1, ADR-0192): a list of fill-ins never reads the
 * cells document.
 *
 * @param fillId the header record of the fill-in
 * @param ownerObject the object of the sheet the fill-in was started on
 * @param ownerId the record the sheet belongs to
 * @param versionNo the version of the sheet the fill-in is pinned to for ever
 * @param versionStatus what that version is now ({@code ACTIVE}, {@code SUPERSEDED}, {@code RETIRED}, or
 *        {@code APPROVED} while the sheet is taken back from the people), so a screen can say that the sheet has moved
 *        on; null when the caller is not shown it
 * @param status {@code DRAFT}, {@code SUBMITTED}, {@code RETURNED} or {@code APPROVED}
 * @param version the row version, to send back when saving the cells
 * @param rowCount how many rows the fill-in has
 * @param cellsBytes the stored size of the cells document
 * @param cellsHash the SHA-256 of the canonical cells document as lower case hex
 * @param corrections how many times a person who may edit after submit changed the cells
 * @param createdAt when it was started
 * @param updatedAt when it last changed
 * @param submittedAt when it was last sent for review, or null
 * @param decidedAt when its last review ended, or null
 * @param archivedAt when its cells were moved to the archive (FF2, ADR-0193), or null while they are in the database;
 *        an archived fill-in is read-only and is read back from the archive on demand
 * @param submittedBy the display name of the person who sent it (its filler, the only person who may send it), for the
 *        footer of the View page (SH5.1, ADR-0263); null until it is sent, for a person who has left, and in lists
 */
public record SheetFillSummaryView(@NotNull UUID fillId, @NotNull String ownerObject, @NotNull UUID ownerId,
        @NotNull Integer versionNo, String versionStatus, @NotNull String status, @NotNull Long version,
        @NotNull Integer rowCount, @NotNull Long cellsBytes, @NotNull String cellsHash, @NotNull Integer corrections,
        @NotNull Instant createdAt, @NotNull Instant updatedAt, Instant submittedAt, Instant decidedAt,
        Instant archivedAt, String submittedBy) {

    /** The facts without the name of the person who sent it (lists and saves). */
    public SheetFillSummaryView(UUID fillId, String ownerObject, UUID ownerId, Integer versionNo, String versionStatus,
            String status, Long version, Integer rowCount, Long cellsBytes, String cellsHash, Integer corrections,
            Instant createdAt, Instant updatedAt, Instant submittedAt, Instant decidedAt, Instant archivedAt) {
        this(fillId, ownerObject, ownerId, versionNo, versionStatus, status, version, rowCount, cellsBytes, cellsHash,
                corrections, createdAt, updatedAt, submittedAt, decidedAt, archivedAt, null);
    }
}
