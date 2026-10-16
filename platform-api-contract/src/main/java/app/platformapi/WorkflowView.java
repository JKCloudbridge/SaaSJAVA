package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One workflow of an object as the setup screens show it (Sprint 22). The condition and the labels are text the
 * organization typed, so the record never prints.
 *
 * @param apiName the permanent name, for example {@code ReviewLargeOrders__c}
 * @param objectApiName the object it belongs to
 * @param label the label people read
 * @param description what the workflow is for
 * @param active whether it starts new executions
 * @param definitionVersion the number of the definition that new executions use (1, 2, ...); it grows only when the
 *        trigger, the condition or the actions change
 * @param trigger when it starts
 * @param condition the condition, or null
 * @param actions what it does, in order
 * @param version the version to send back when changing it
 */
public record WorkflowView(@NotNull String apiName, @NotNull String objectApiName, @NotNull String label,
        @NotNull String description, @NotNull Boolean active, @NotNull Integer definitionVersion,
        @NotNull WorkflowTriggerSpec trigger, String condition, @NotNull List<WorkflowActionSpec> actions,
        @NotNull Long version) {

    /** Copies the list. */
    public WorkflowView {
        actions = List.copyOf(actions);
    }

    @Override
    public String toString() {
        return "WorkflowView[redacted]";
    }
}
