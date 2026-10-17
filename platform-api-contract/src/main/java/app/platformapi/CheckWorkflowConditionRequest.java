package app.platformapi;

import jakarta.validation.constraints.Size;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Asks whether a condition would be true for a record with sample values (Sprint 22). Nothing is stored and nothing is
 * logged. The text is typed by a person, so the record never prints.
 *
 * @param condition the condition to try; left out, "always runs" is the answer
 * @param values sample values by field API name, each written as text for the type of its field; a field left out or
 *        blank has no value
 */
public record CheckWorkflowConditionRequest(@Size(max = 4000) String condition, Map<String, String> values) {

    /** Copies the values. */
    public CheckWorkflowConditionRequest {
        Map<String, String> copy = new HashMap<>(values == null ? Map.of() : values);
        copy.values().removeIf(Objects::isNull);
        values = Map.copyOf(copy);
    }

    @Override
    public String toString() {
        return "CheckWorkflowConditionRequest[redacted]";
    }
}
