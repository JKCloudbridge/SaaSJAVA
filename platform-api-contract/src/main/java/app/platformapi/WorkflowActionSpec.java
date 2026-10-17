package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * One action of a workflow (Sprint 22, ADR-0136). The type is one of the closed list of {@link WorkflowActionTypes};
 * the settings are the ones that type takes. An action never carries text that is run.
 *
 * @param type the kind of action, for example {@code PUBLISH_EVENT}
 * @param name a name for this step, unique inside the workflow (letters, digits and underscores); the execution
 *        history shows it
 * @param settings the settings of the type, as text
 */
public record WorkflowActionSpec(@NotBlank @Size(max = 40) String type, @NotBlank @Size(max = 60) String name,
        @Size(max = 10) Map<@NotBlank @Size(max = 40) String, @Size(max = 255) String> settings) {

    /** Copies the settings. */
    public WorkflowActionSpec {
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }

    @Override
    public String toString() {
        return "WorkflowActionSpec[" + type + "]";
    }
}
