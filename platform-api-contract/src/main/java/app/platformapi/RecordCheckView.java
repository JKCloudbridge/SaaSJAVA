package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * The answer to a validation that found nothing wrong (Sprint 19, ADR-0129). A save that would be refused is answered
 * with {@code VALIDATION_ERROR} and the places that are wrong, exactly as a real save would be; this is only what a
 * save that would work adds. The values are what the formulas gave, so the answer never prints.
 *
 * @param calculated the results the formulas of the record would have after this save, for the formula fields the
 *        member may read and whose result this save would change. A formula whose result stays as it is is left out
 * @param cleared the formula fields the member may read that would have no result after this save
 */
public record RecordCheckView(@NotNull Map<String, Object> calculated, @NotNull List<String> cleared) {

    /** Copies what is given. */
    public RecordCheckView {
        calculated = calculated == null ? Map.of() : Map.copyOf(calculated);
        cleared = cleared == null ? List.of() : List.copyOf(cleared);
    }

    @Override
    public String toString() {
        return "RecordCheckView[redacted]";
    }
}
