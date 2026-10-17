package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * One step of an execution: one action of the workflow (Sprint 22). No value of a record and no text of the
 * organization is kept here.
 *
 * @param position the place of the action in the workflow, from 1
 * @param name the name of the action in the workflow
 * @param type the type of the action
 * @param status {@code RUNNING}, {@code COMPLETED} or {@code FAILED}
 * @param startedAt when it started
 * @param finishedAt when it ended, or null
 * @param failureType for {@code FAILED}: the type name of the failure, never its message
 */
public record WorkflowStepView(@NotNull Integer position, @NotNull String name, @NotNull String type,
        @NotNull String status, @NotNull Instant startedAt, Instant finishedAt, String failureType) {
}
