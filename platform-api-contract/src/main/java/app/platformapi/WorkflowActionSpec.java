package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * One action of a workflow (Sprint 22, ADR-0136; assignments and attempts Sprint 23, ADR-0138). The type is one of the
 * closed list of {@link WorkflowActionTypes}; the settings are the ones that type takes. An action never carries text
 * that is run.
 *
 * @param type the kind of action, for example {@code PUBLISH_EVENT}
 * @param name a name for this step, unique inside the workflow (letters, digits and underscores); the execution
 *        history shows it
 * @param settings the settings of the type, as text
 * @param assignments the fields the action sets (the types that change records), each a literal or a copy
 * @param maxAttempts a number of attempts lower than the platform's, or left out to use the platform's; it can only
 *        lower the number (ADR-0142)
 */
public record WorkflowActionSpec(@NotBlank @Size(max = 40) String type, @NotBlank @Size(max = 60) String name,
        @Size(max = 10) Map<@NotBlank @Size(max = 40) String, @Size(max = 255) String> settings,
        @Size(max = 100) List<@NotNull @Valid WorkflowAssignmentSpec> assignments,
        @Min(1) @Max(100) Integer maxAttempts) {

    /** Copies the settings and the assignments. */
    public WorkflowActionSpec {
        settings = settings == null ? Map.of() : Map.copyOf(settings);
        assignments = assignments == null ? List.of() : List.copyOf(assignments);
    }

    /** An action with no assignments and the platform's attempts (the shape of Sprint 22). */
    public WorkflowActionSpec(String type, String name, Map<String, String> settings) {
        this(type, name, settings, List.of(), null);
    }

    @Override
    public String toString() {
        return "WorkflowActionSpec[" + type + "]";
    }
}
