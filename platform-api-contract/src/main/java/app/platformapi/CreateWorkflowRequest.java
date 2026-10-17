package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Adds a workflow to an object (Sprint 22, ADR-0136): when something happens to a record of the object (or at a time),
 * if the condition is true, run the actions. The text is typed by a person, so it never prints.
 *
 * @param name the name to build the API name from; the platform stores it with the ending {@code __c}
 * @param label the label people read
 * @param description what the workflow is for, optional
 * @param active whether the workflow starts new executions (default true)
 * @param trigger when it starts
 * @param condition an optional condition in the expression language; it must give true or false and may use
 *        {@code TODAY()} and {@code NOW()}. It is judged on the record as it is when the execution runs. Left out, the
 *        workflow always runs
 * @param actions what it does, in order (at least one)
 */
public record CreateWorkflowRequest(@NotBlank @Size(max = 40) String name, @NotBlank @Size(max = 80) String label,
        @Size(max = 500) String description, Boolean active, @NotNull @Valid WorkflowTriggerSpec trigger,
        @Size(max = 4000) String condition,
        @NotEmpty @Size(max = 20) List<@NotNull @Valid WorkflowActionSpec> actions) {

    /** Copies the list. */
    public CreateWorkflowRequest {
        actions = actions == null ? null : List.copyOf(actions);
    }

    @Override
    public String toString() {
        return "CreateWorkflowRequest[redacted]";
    }
}
