package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * One publication of the organization's metadata (Sprint 11): the history that rollback works on.
 *
 * @param number 1, 2, 3 ... in the order of publication
 * @param kind {@code QUICK} (one change made at once), {@code CHANGE_SET} or {@code ROLLBACK}
 * @param changeSetName the name of the change set for {@code CHANGE_SET}, or null
 * @param undoesRelease for {@code ROLLBACK}, the number of the release it undid, or null
 * @param rolledBackBy the number of the rollback that undid this release, or null
 * @param createdAt when it was published
 * @param latest whether it is the newest release (only the newest can be rolled back)
 * @param items what it added, changed or removed
 */
public record ReleaseView(@NotNull Long number, @NotNull String kind, String changeSetName, Long undoesRelease,
        Long rolledBackBy, @NotNull Instant createdAt, @NotNull Boolean latest, @NotNull List<ReleaseItemView> items) {

    /** Copies the list. */
    public ReleaseView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
