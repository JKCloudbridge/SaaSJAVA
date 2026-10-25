package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * One version of a sheet without its grid (Functional track FA3, ADR-0186): what a list of versions shows. Reading a
 * list never reads the grids, so a long history costs the same as a short one (ADR-0173 section 5).
 *
 * @param versionNo the number of the version inside its sheet; given automatically, never reused
 * @param status {@code DRAFT}, {@code IN_REVIEW}, {@code APPROVED}, {@code ACTIVE}, {@code SUPERSEDED},
 *        {@code REJECTED}, {@code WITHDRAWN} or {@code RETIRED} (ADR-0174)
 * @param dimension 1 when the filler adds the rows, 2 when the sheet names the rows
 * @param rowsMode {@code FIXED}, {@code REPEATABLE} or {@code FIXED_AND_REPEATABLE}
 * @param gridBytes the size of the grid as stored, in bytes
 * @param gridHash the SHA-256 of the grid in its canonical form, in lower case hex: the same grid always has the same
 *        hash, whatever order its keys were written in
 * @param version the version to send back when changing it
 * @param createdAt when the version was made
 * @param updatedAt when it last changed
 * @param submittedAt when it was sent for approval, if it was
 * @param decidedAt when its review ended, if it did
 * @param reviewEdits how many times an approver changed the grid while it was in review (FA4); each change is audited
 * @param updatedBy the display name of the person who last changed the version (UI3B), or absent when that person
 *        has left or the platform did it: never an identifier
 * @param revisionNo the revision number people read (0 for the first), set by the database when the version first
 *        became the one in use (FA8, ADR-0202); absent for a version that never was, and for one that became so before
 *        numbers were stored (a screen then reads the version number minus one)
 * @param held whether this draft belongs to an approval request (FA8): only the people asked to decide that request,
 *        and those who manage access, change it, and a person cannot start another draft beside it
 */
public record SheetVersionSummaryView(@NotNull Integer versionNo, @NotNull String status, @NotNull Integer dimension,
        @NotNull String rowsMode, @NotNull Long gridBytes, @NotNull String gridHash, @NotNull Long version,
        @NotNull Instant createdAt, @NotNull Instant updatedAt, Instant submittedAt, Instant decidedAt,
        @NotNull Integer reviewEdits, String updatedBy, Integer revisionNo, @NotNull Boolean held) {
}
