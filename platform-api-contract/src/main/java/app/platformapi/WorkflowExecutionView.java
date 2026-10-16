package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One run of a workflow for one cause (Sprint 22, ADR-0137): a record event or one firing of a schedule. It keeps the
 * facts of the run and never a value of the record, the condition or anything a person typed.
 *
 * @param id the identifier of the execution
 * @param workflow the API name of the workflow
 * @param objectApiName the object of the record
 * @param workflowVersion the number of the definition the execution was created under; a later publication does not
 *        change it
 * @param trigger what caused it ({@code RECORD_CREATED}, ..., or a scheduled kind)
 * @param status {@code QUEUED}, {@code RUNNING}, {@code COMPLETED}, {@code FAILED}, {@code CANCELLED} or
 *        {@code SKIPPED}
 * @param reason a stable key that says why an execution was skipped, cancelled or failed (for example
 *        {@code condition_false}, {@code record_gone}, {@code feature_off}), or null
 * @param recordId the record it ran for
 * @param recordVersion the version of the record the event announced, or null for a firing of a schedule
 * @param attempts how many times a worker claimed it (more than one after a worker died)
 * @param queuedAt when it was created
 * @param startedAt when a worker first started it, or null
 * @param finishedAt when it reached its final status, or null
 * @param failureType for {@code FAILED}: the type name of the failure, never its message
 * @param steps its steps, in order (only on a single execution)
 */
public record WorkflowExecutionView(@NotNull UUID id, @NotNull String workflow, @NotNull String objectApiName,
        @NotNull Integer workflowVersion, @NotNull String trigger, @NotNull String status, String reason,
        @NotNull UUID recordId, Long recordVersion, @NotNull Integer attempts, @NotNull Instant queuedAt,
        Instant startedAt, Instant finishedAt, String failureType, List<WorkflowStepView> steps) {

    /** Copies the list. */
    public WorkflowExecutionView {
        steps = steps == null ? null : List.copyOf(steps);
    }
}
