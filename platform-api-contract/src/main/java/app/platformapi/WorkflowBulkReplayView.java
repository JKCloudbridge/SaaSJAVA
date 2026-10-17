package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The results of a bulk replay, in the order asked (Sprint 23).
 *
 * @param results one result per execution
 */
public record WorkflowBulkReplayView(@NotNull List<WorkflowReplayResultView> results) {

    /** Copies the list. */
    public WorkflowBulkReplayView {
        results = List.copyOf(results);
    }
}
