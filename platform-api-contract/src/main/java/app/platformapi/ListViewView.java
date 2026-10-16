package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * A saved list view of one object (Sprint 19, ADR-0130). A personal view is visible to the person who made it only; a
 * shared one to everyone in the organization who may read the object. The label is typed by a person, so it never
 * prints.
 *
 * @param id the identifier
 * @param objectApiName the object it is a view of
 * @param label the name of the view
 * @param scope {@code PERSONAL} or {@code SHARED}
 * @param mine whether the person asking made it: only they may change or remove a personal view, and a shared one is
 *        changed by its maker or by someone who may manage shared views
 * @param definition the filter, the order and the columns
 * @param version the version to send back when changing it
 * @param updatedAt when it was last changed
 */
public record ListViewView(@NotNull String id, @NotNull String objectApiName, @NotNull String label,
        @NotNull String scope, @NotNull Boolean mine, @NotNull ListViewDefinition definition, @NotNull Long version,
        @NotNull Instant updatedAt) {

    @Override
    public String toString() {
        return "ListViewView[redacted]";
    }
}
