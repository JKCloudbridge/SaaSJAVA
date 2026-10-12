package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * A change set (Sprint 11).
 *
 * @param id the identifier
 * @param name the name people read
 * @param description what the group is for
 * @param status {@code DRAFT} (open), {@code PUBLISHED} or {@code DISCARDED}
 * @param releaseNumber the release that published it, or null
 * @param changeCount how many changes it holds
 * @param createdAt when it was started
 * @param version the version to send back when changing it
 * @param changes the changes in the order they are applied (empty in the list of change sets)
 */
public record ChangeSetView(@NotNull String id, @NotNull String name, @NotNull String description,
        @NotNull String status, Long releaseNumber, @NotNull Integer changeCount, @NotNull Instant createdAt,
        @NotNull Long version, @NotNull List<ChangeView> changes) {

    /** Copies the changes. */
    public ChangeSetView {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    @Override
    public String toString() {
        return "ChangeSetView[redacted]";
    }
}
