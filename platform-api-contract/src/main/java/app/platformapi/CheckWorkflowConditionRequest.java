package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Asks whether a condition would be true for a record with sample values, and whether the actions fit the object
 * (Sprint 22; actions Sprint 23). Nothing is stored and nothing is logged. The text is typed by a person, so the record
 * never prints.
 *
 * @param condition the condition to try; left out, "always runs" is the answer
 * @param values sample values by field API name, each written as text for the type of its field; a field left out or
 *        blank has no value
 * @param actions actions to try against the object: whether the fields they name exist and can be set, whether the
 *        literals fit, and what is still missing; left out, none are tried
 */
public record CheckWorkflowConditionRequest(@Size(max = 4000) String condition, Map<String, String> values,
        @Size(max = 20) List<@NotNull @Valid WorkflowActionSpec> actions) {

    /** Copies the values and the actions. */
    public CheckWorkflowConditionRequest {
        Map<String, String> copy = new HashMap<>(values == null ? Map.of() : values);
        copy.values().removeIf(Objects::isNull);
        values = Map.copyOf(copy);
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    /** A request that tries a condition only (the shape of Sprint 22). */
    public CheckWorkflowConditionRequest(String condition, Map<String, String> values) {
        this(condition, values, List.of());
    }

    @Override
    public String toString() {
        return "CheckWorkflowConditionRequest[redacted]";
    }
}
