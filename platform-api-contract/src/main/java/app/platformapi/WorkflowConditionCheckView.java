package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * What a workflow condition does for a record with the sample values, and whether the actions fit the object (Sprint
 * 22; actions Sprint 23). Nothing was stored or logged.
 *
 * @param outcome {@code RUNS} (the condition is true, or there is none), {@code SKIPPED} (it is false or has no
 *        value: the execution would end with the reason {@code condition_false}), or {@code ERROR} (it cannot be
 *        evaluated for these values, for example a division by zero: the execution would end with the reason
 *        {@code condition_not_evaluable})
 * @param problem for {@code ERROR}: what went wrong and where
 * @param actionProblems what is wrong with the actions that were tried, by place (for example
 *        {@code actions[0].assignments[1].value}); empty when they fit the object
 */
public record WorkflowConditionCheckView(@NotNull String outcome, ExpressionProblemView problem,
        @NotNull Map<String, List<String>> actionProblems) {

    /** Copies the problems. */
    public WorkflowConditionCheckView {
        actionProblems = actionProblems == null ? Map.of() : Map.copyOf(actionProblems);
    }

    /** A check without actions. */
    public WorkflowConditionCheckView(String outcome, ExpressionProblemView problem) {
        this(outcome, problem, Map.of());
    }
}
