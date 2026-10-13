package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * The indexed fields of an object and where each stands (Sprint 16), for the administrators who flag them.
 *
 * @param objectApiName the object
 * @param fields every flagged field and every field whose index is still being removed
 * @param slots how many fields of each kind of value the object can have indexed, by the kind ({@code text},
 *        {@code number}, {@code date}, {@code date and time}, {@code link}, {@code checkbox})
 */
public record ObjectIndexStatusView(@NotNull String objectApiName, @NotNull List<FieldIndexStatusView> fields,
        @NotNull Map<String, Integer> slots) {

    /** Copies the list and the map. */
    public ObjectIndexStatusView {
        fields = List.copyOf(fields);
        slots = Map.copyOf(slots);
    }
}
