package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * How many dead-lettered executions wait for a person, by workflow and reason (Sprint 23, ADR-0143).
 *
 * @param total all of them
 * @param groups the counts by workflow and stable reason, largest first
 */
public record WorkflowDeadLetterSummaryView(@NotNull Long total, @NotNull List<DeadLetterGroup> groups) {

    /** Copies the list. */
    public WorkflowDeadLetterSummaryView {
        groups = List.copyOf(groups);
    }

    /**
     * One group.
     *
     * @param workflow the API name of the workflow
     * @param objectApiName its object
     * @param reason the stable reason key
     * @param count how many
     */
    public record DeadLetterGroup(@NotNull String workflow, @NotNull String objectApiName, @NotNull String reason,
            @NotNull Long count) {
    }
}
