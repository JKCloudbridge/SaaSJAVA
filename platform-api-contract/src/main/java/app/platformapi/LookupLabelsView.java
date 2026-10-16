package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * The names of linked records (Sprint 19, ADR-0129), by identifier. An identifier that is not here names a record the
 * member may not read, one that does not exist and one that is no longer available, and the answer does not say which.
 * The names are typed by people, so the answer never prints.
 *
 * @param labels the name of each record that could be named
 */
public record LookupLabelsView(@NotNull Map<String, String> labels) {

    /** Copies the map. */
    public LookupLabelsView {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }

    @Override
    public String toString() {
        return "LookupLabelsView[redacted]";
    }
}
