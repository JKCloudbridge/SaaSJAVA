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
 */
public record SheetFillSummaryView(@NotNull UUID fillId, @NotNull String ownerObject, @NotNull UUID ownerId,
        @NotNull Integer versionNo, String versionStatus, @NotNull String status, @NotNull Long version,
        @NotNull Integer rowCount, @NotNull Long cellsBytes, @NotNull String cellsHash, @NotNull Integer corrections,
        @NotNull Instant createdAt, @NotNull Instant updatedAt, Instant submittedAt, Instant decidedAt) {
}
