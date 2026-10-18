package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Asks whether entry criteria would be true for a record with sample values, and what is wrong with the steps and the
 * outcomes for the object (Sprint 24, ADR-0145). Nothing is stored and nothing is logged. The text is typed by a
 * person,
 * so the record never prints. Who would be asked for a real record is another question, answered by the resolve
 * endpoint of the approval module.
 *
 * @param entryCriteria the condition to try; left out, "any record" is the answer
 * @param values sample values by field API name, each written as text for the type of its field; a field left out or
 *        blank has no value
 * @param steps steps to try against the object; left out, none are tried
 * @param outcomes outcomes to try against the object; left out, none are tried
 */
public record CheckApprovalProcessRequest(@Size(max = 4000) String entryCriteria, Map<String, String> values,
        @Size(max = 20) List<@NotNull @Valid ApprovalStepSpec> steps, @Valid ApprovalOutcomesSpec outcomes) {

    /** Copies the values and the steps. */
    public CheckApprovalProcessRequest {
        Map<String, String> copy = new HashMap<>(values == null ? Map.of() : values);
        copy.values().removeIf(Objects::isNull);
        values = Map.copyOf(copy);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    @Override
    public String toString() {
        return "CheckApprovalProcessRequest[redacted]";
    }
}
