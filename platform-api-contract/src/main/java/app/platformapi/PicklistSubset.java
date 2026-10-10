package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The values of one picklist field that a record type allows (Sprint 11). A picklist that a record type does not name
 * allows all its values.
 *
 * @param field the API name of the picklist or multi-picklist field
 * @param values the values (not the labels) the record type allows; at least one
 */
public record PicklistSubset(@NotBlank @Size(max = 60) String field,
        @NotNull @Size(min = 1, max = 1000) List<@NotBlank @Size(max = 80) String> values) {

    /** Copies the values. */
    public PicklistSubset {
        values = values == null ? null : List.copyOf(values);
    }
}
