package app.platformapi;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Replays several dead-lettered executions (Sprint 23, ADR-0141): one result per execution, each in its own savepoint.
 * There is no all-or-nothing form.
 *
 * @param executions the executions, at most 200
 */
public record WorkflowBulkReplayRequest(@NotEmpty @Size(max = 200) List<@NotNull UUID> executions) {

    /** Copies the list. */
    public WorkflowBulkReplayRequest {
        executions = executions == null ? null : List.copyOf(executions);
    }
}
