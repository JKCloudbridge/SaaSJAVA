package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * One answer of a formula (SH2): its kind and its value as text.
 *
 * @param kind number, text, boolean or error
 * @param value the digits of a number (fifteen significant digits), the text itself, TRUE or FALSE, or the code of an
 *        error such as {@code #DIV/0!}
 */
public record SheetCalculatedAnswerView(@NotNull String kind, @NotNull String value) {
}
