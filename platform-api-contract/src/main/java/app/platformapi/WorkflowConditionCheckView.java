package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * What a workflow condition does for a record with the sample values (Sprint 22). Nothing was stored or logged.
 *
 * @param outcome {@code RUNS} (the condition is true, or there is none), {@code SKIPPED} (it is false or has no
 *        value: the execution would end with the reason {@code condition_false}), or {@code ERROR} (it cannot be
 *        evaluated for these values, for example a division by zero: the execution would end with the reason
 *        {@code condition_not_evaluable})
 * @param problem for {@code ERROR}: what went wrong and where
 */
public record WorkflowConditionCheckView(@NotNull String outcome, ExpressionProblemView problem) {
}
