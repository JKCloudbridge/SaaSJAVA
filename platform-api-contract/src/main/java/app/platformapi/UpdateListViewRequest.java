package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Changes a saved list view (Sprint 19, ADR-0130). The request carries the version that was read: if the view has
 * changed since, nothing is changed and the answer is {@code CONCURRENT_MODIFICATION}. Typed text never prints.
 *
 * @param label the name of the view
 * @param scope {@code PERSONAL} or {@code SHARED}; a change of scope needs the same right as making a view of that
 *        scope
 * @param definition the filter, the order and the columns
 * @param version the version of the view that the change is based on
 */
public record UpdateListViewRequest(@NotBlank @Size(max = 80) String label, @NotNull @Size(max = 10) String scope,
        @NotNull @Valid ListViewDefinition definition, @NotNull Long version) {

    @Override
    public String toString() {
        return "UpdateListViewRequest[redacted]";
    }
}
