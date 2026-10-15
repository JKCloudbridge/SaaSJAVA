package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Saves a list view (Sprint 19, ADR-0130). Typed text never prints.
 *
 * @param label the name of the view, 1 to 80 characters, unique among the views of the object a person sees
 * @param scope {@code PERSONAL} (only the maker sees it) or {@code SHARED} (everyone who may read the object; needs the
 *        ability to share list views)
 * @param definition the filter, the order and the columns
 */
public record CreateListViewRequest(@NotBlank @Size(max = 80) String label, @NotNull @Size(max = 10) String scope,
        @NotNull @Valid ListViewDefinition definition) {

    @Override
    public String toString() {
        return "CreateListViewRequest[redacted]";
    }
}
