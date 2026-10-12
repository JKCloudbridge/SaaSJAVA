package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * What would happen to a record with the sample values (Sprint 13). Nothing was stored or logged.
 *
 * @param outcome {@code REJECTED} (a rule is true: the save would be refused, and {@code message} is what the person
 *        would read), {@code PASSED} (a rule is false or has no value), {@code VALUE} (a formula gave a value) or
 *        {@code ERROR} (the evaluation itself failed, for example a division by zero; see {@code problem})
 * @param type the type of the value of a formula or rule
 * @param value for a formula: the value written as text, or null when it has no value
 * @param message for {@code REJECTED}: the message of the rule
 * @param problem for {@code ERROR}: what went wrong and where
 */
public record ExpressionTestView(@NotNull String outcome, String type, String value, String message,
        ExpressionProblemView problem) {

    @Override
    public String toString() {
        return "ExpressionTestView[redacted]";
    }
}
