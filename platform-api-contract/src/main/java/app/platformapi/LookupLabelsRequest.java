package app.platformapi;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Asks for the names of the records a link field points to (Sprint 19, ADR-0129): the values a list or a form holds,
 * as identifiers.
 *
 * @param ids the identifiers of the linked records, at most 100 (one page of a list is at most 200 records, and a
 *        list asks once per link field for the distinct identifiers on the page)
 */
public record LookupLabelsRequest(@NotNull @NotEmpty @Size(max = 100) List<@NotNull @Size(max = 36) String> ids) {

    /** Copies the list. */
    public LookupLabelsRequest {
        ids = ids == null ? List.of() : List.copyOf(ids);
    }
}
