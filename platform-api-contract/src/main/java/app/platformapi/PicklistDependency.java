package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * The rule that makes a single-value picklist depend on another picklist of the same object (FA2A, ADR-0177): for each
 * stored value of the controlling field, the stored values of this field that go with it. The values are text the
 * organization chose, so the rule never prints.
 *
 * @param controllingField the API name of the controlling field (a single-value picklist of the same object that
 *        depends
 *        on nothing itself)
 * @param allowed for each stored value of the controlling field, the stored values of this field that go with it; a
 *        controlling value with an empty list allows nothing
 */
public record PicklistDependency(@NotBlank @Size(max = 60) String controllingField,
        @NotNull @Size(max = 200) Map<String, List<String>> allowed) {

    /** Copies what is given. */
    public PicklistDependency {
        allowed = allowed == null ? Map.of() : Map.copyOf(allowed);
    }

    @Override
    public String toString() {
        return "PicklistDependency[redacted]";
    }
}
