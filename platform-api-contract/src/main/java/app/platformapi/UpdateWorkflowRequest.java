package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Changes a workflow (Sprint 22, ADR-0136). The API name and the object never change. A change of the trigger, the
 * condition or the actions makes a new version of the definition; a change of the label, the description or only
 * whether it is active does not, and executions already queued keep the version they were created under.
 *
 * @param label the label people read
 * @param description what the workflow is for, optional
 * @param active whether the workflow starts new executions
 * @param trigger when it starts
 * @param condition an optional condition; true or false, judged when the execution runs
 * @param actions what it does, in order
 * @param version the version of the workflow the caller last read; a change on top of a newer one is refused
 */
public record UpdateWorkflowRequest(@NotBlank @Size(max = 80) String label, @Size(max = 500) String description,
        @NotNull Boolean active, @NotNull @Valid WorkflowTriggerSpec trigger, @Size(max = 4000) String condition,
        @NotEmpty @Size(max = 20) List<@NotNull @Valid WorkflowActionSpec> actions, @NotNull Long version) {

    /** Copies the list. */
    public UpdateWorkflowRequest {
        actions = actions == null ? null : List.copyOf(actions);
    }

    @Override
    public String toString() {
        return "UpdateWorkflowRequest[redacted]";
    }
}
