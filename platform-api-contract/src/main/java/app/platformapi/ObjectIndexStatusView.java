package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * The indexed fields of an object and where each stands (Sprint 16), and the unique fields (Sprint 16A), for the
 * administrators who flag them.
 *
 * @param objectApiName the object
 * @param fields every flagged field and every field whose index is still being removed
 * @param slots how many fields of each kind of value the object can have indexed or unique, by the kind ({@code text},
 *        {@code number}, {@code date}, {@code date and time}, {@code link}, {@code checkbox})
 * @param uniqueness every unique field of the object, and the pair of parents of a junction, and where the
 *        enforcement of each stands
 * @param search every searchable field of the object and where its words stand (Sprint 17); a field is searched only
 *        when its words are complete
 */
public record ObjectIndexStatusView(@NotNull String objectApiName, @NotNull List<FieldIndexStatusView> fields,
        @NotNull Map<String, Integer> slots, @NotNull List<FieldUniquenessStatusView> uniqueness,
        @NotNull List<FieldSearchStatusView> search) {

    /** Copies the lists and the map. */
    public ObjectIndexStatusView {
        fields = List.copyOf(fields);
        slots = Map.copyOf(slots);
        uniqueness = List.copyOf(uniqueness);
        search = List.copyOf(search);
    }
}
