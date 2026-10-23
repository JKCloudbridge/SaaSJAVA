package app.platform.inspection.internal;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the endpoints of the fill-ins of the cluster send (FF1, ADR-0192). They live here and not in the API contract
 * module because they carry the cluster's words; the OpenAPI document and the generated client carry them all the same.
 *
 * <p>Everything in them is for a screen to draw, never to decide: the flags say what to offer, and every request that
 * follows is decided again by the server. No view carries a cell or a person's identifier.
 */
public final class FillInViews {

    private FillInViews() {
    }

    /**
     * Whether the caller may start a fill-in on a template, and on which version.
     *
     * @param canStart whether to offer Start: the caller may start fill-ins, the template is not retired and a version
     *        is in use
     * @param activeVersion the version a fill-in started now would be made on, or 0 when none is in use
     * @param reason why not, in words for a person, or null when canStart is true. Never a name
     */
    public record FillInOptionsView(@NotNull Boolean canStart, @NotNull Integer activeVersion, String reason) {
    }

    /**
     * A fill-in just started.
     *
     * @param fillId the header record; the cells are read and saved through the sheet fill-in endpoints
     * @param templateId the template it was started on
     * @param versionNo the version it is pinned to for ever
     */
    public record FillInStartedView(@NotNull UUID fillId, @NotNull UUID templateId, @NotNull Integer versionNo) {
    }

    /**
     * One fill-in in a list. There are no cells: a list never reads them.
     *
     * @param fillId the header record
     * @param templateId the template it was started on
     * @param name the name shown for it (that of the template when it was started), or null
     * @param status {@code DRAFT}, {@code SUBMITTED}, {@code RETURNED} or {@code APPROVED}
     * @param versionNo the version of the template it is pinned to
     * @param versionInUse whether that version is still the one in use, so a screen can say that the template has
     *        moved on
     * @param rowCount how many rows it has
     * @param filledBy the display name of the person who owns it, or null when they have left or the name is not shown
     * @param createdAt when it was started
     * @param updatedAt when it last changed
     * @param submittedAt when it was last sent for review, or null
     */
    public record FillInRow(@NotNull UUID fillId, @NotNull UUID templateId, String name, @NotNull String status,
            @NotNull Integer versionNo, @NotNull Boolean versionInUse, @NotNull Integer rowCount, String filledBy,
            @NotNull Instant createdAt, @NotNull Instant updatedAt, Instant submittedAt) {
    }

    /**
     * One page of fill-ins, newest first, paged by the key of the last one seen.
     *
     * @param rows the fill-ins the caller may see
     * @param nextCursor the cursor of the next page of the same query, or null on the last page
     */
    public record FillInPage(@NotNull List<FillInRow> rows, String nextCursor) {
    }
}
