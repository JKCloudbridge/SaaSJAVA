package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One run of a workflow for one cause (Sprint 22, ADR-0137): a record event, one firing of a schedule, or a person's
 * replay of an earlier run (Sprint 23, ADR-0141). It keeps the facts of the run and never a value of the record, the
 * condition or anything a person typed.
 *
 * @param id the identifier of the execution
 * @param workflow the API name of the workflow
 * @param objectApiName the object of the record
 * @param workflowVersion the number of the definition the execution was created under; a later publication does not
 *        change it
 * @param trigger what caused it ({@code RECORD_CREATED}, ..., or a scheduled kind)
 * @param status {@code QUEUED}, {@code RUNNING}, {@code COMPLETED}, {@code CANCELLED}, {@code SKIPPED},
 *        {@code DEAD_LETTERED} (retries are spent or the failure is permanent: it needs a person) or {@code FAILED}
 *        (only runs made before Sprint 23)
 * @param reason a stable key that says why an execution was skipped, cancelled, waits or ended (for example
 *        {@code condition_false}, {@code record_gone}, {@code feature_off}, {@code retry_scheduled},
 *        {@code loop_detected}, {@code unique_clash}), or null
 * @param recordId the record it ran for
 * @param recordVersion the version of the record the event announced, or null for a firing of a schedule
 * @param attempts how many times a worker claimed it (more than one after a worker died)
 * @param queuedAt when it was created
 * @param startedAt when a worker first started it, or null
 * @param finishedAt when it reached its final status, or null
 * @param failureType for a failed or dead-lettered run: the type name of the failure, never its message
 * @param steps its steps, in order (only on a single execution)
 * @param replayOf the execution this one replays, or null
 * @param replayedBy the execution that replays this one, or null
 * @param replayGeneration 0 for a run caused by an event or a firing, 1 for the replay of such a run, and so on
 * @param chainDepth how many workflow executions lead to this one (0 when a person's write started it)
 * @param resumeStep the first step this run carries out (more than 1 for a replay that resumes after steps that
 *        completed)
 * @param runAt when it is due while it waits (a retry, or a delay by the rate guard), otherwise when it was queued
 */
public record WorkflowExecutionView(@NotNull UUID id, @NotNull String workflow, @NotNull String objectApiName,
        @NotNull Integer workflowVersion, @NotNull String trigger, @NotNull String status, String reason,
        @NotNull UUID recordId, Long recordVersion, @NotNull Integer attempts, @NotNull Instant queuedAt,
        Instant startedAt, Instant finishedAt, String failureType, List<WorkflowStepView> steps, UUID replayOf,
        UUID replayedBy, @NotNull Integer replayGeneration, @NotNull Integer chainDepth, @NotNull Integer resumeStep,
        @NotNull Instant runAt) {

    /** Copies the list. */
    public WorkflowExecutionView {
        steps = steps == null ? null : List.copyOf(steps);
    }
}
