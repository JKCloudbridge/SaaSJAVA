package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * What entry criteria do for a record with sample values, and what is wrong with the steps and the outcomes (Sprint
 * 24).
 * Nothing was stored or logged.
 *
 * @param outcome {@code RUNS} (the criteria are true, or there are none), {@code SKIPPED} (false or no value: the
 * record
 *        could not be submitted) or {@code ERROR} (they cannot be evaluated for these values)
 * @param problem for {@code ERROR}: what went wrong and where
 * @param problems what is wrong with the steps and the outcomes that were tried, by place
 */
public record ApprovalProcessCheckView(@NotNull String outcome, ExpressionProblemView problem,
        @NotNull Map<String, List<String>> problems) {

    /** Copies the problems. */
    public ApprovalProcessCheckView {
        problems = problems == null ? Map.of() : Map.copyOf(problems);
    }
}
