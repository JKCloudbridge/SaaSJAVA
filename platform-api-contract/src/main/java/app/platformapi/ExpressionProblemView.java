package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * What is wrong with an expression and where (Sprint 13). The message is plain words about the kind of problem; it
 * never repeats what the person typed except the name of a field or a function.
 *
 * @param kind the kind, for example {@code SYNTAX}, {@code UNKNOWN_FIELD}, {@code TYPE}, {@code TOO_LONG},
 *        {@code DIVISION_BY_ZERO}
 * @param message the words, with the place ("Line 1, column 14: ...")
 * @param line the line of the place, counting from 1, or null when the problem is about the expression as a whole
 * @param column the column of the place, counting from 1, or null
 */
public record ExpressionProblemView(@NotNull String kind, @NotNull String message, Integer line, Integer column) {
}
