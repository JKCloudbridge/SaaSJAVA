package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * One step of an execution: one action of the workflow (Sprint 22; attempts Sprint 23, ADR-0142). No value of a record
 * and no text of the organization is kept here.
 *
 * @param position the place of the action in the workflow, from 1
 * @param name the name of the action in the workflow
 * @param type the type of the action
 * @param status {@code RUNNING}, {@code RETRYING} (waiting for its next attempt), {@code COMPLETED} or {@code FAILED}
 * @param startedAt when it started
 * @param finishedAt when it ended, or null
 * @param failureType for a failed or retrying step: the type name of the last failure, never its message
 * @param attempts how many attempts of this step ended (a worker that died mid-attempt is not counted here)
 * @param nextAttemptAt for {@code RETRYING}: when the next attempt is due
 * @param reason for a failed or retrying step: a stable key that says why (for example {@code unique_clash},
 *        {@code retry_scheduled}), or null
 */
public record WorkflowStepView(@NotNull Integer position, @NotNull String name, @NotNull String type,
        @NotNull String status, @NotNull Instant startedAt, Instant finishedAt, String failureType,
        @NotNull Integer attempts, Instant nextAttemptAt, String reason) {
}
