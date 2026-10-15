package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * What a saved list view is made of (Sprint 19, ADR-0130): the filter, the order and the columns of a list of records,
 * in the shape of a record query so that the list runs it as it is. It is data, never code: names are looked up in the
 * metadata when it runs and values are data. It carries what a person typed, so it never prints.
 *
 * @param filter the conditions; left out means every record
 * @param sort at most two keys; left out means identifier order
 * @param columns the API names of the fields shown, in order; left out means the platform's choice
 */
public record ListViewDefinition(@Valid QueryFilterNode filter,
        @Size(max = 2) List<@NotNull @Valid QuerySortKey> sort,
        @Size(max = 30) List<@NotBlank @Size(max = 80) String> columns) {

    /** Copies the lists. */
    public ListViewDefinition {
        sort = sort == null ? List.of() : List.copyOf(sort);
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    @Override
    public String toString() {
        return "ListViewDefinition[redacted]";
    }
}
