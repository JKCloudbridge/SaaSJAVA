package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * The answer of a formula cell (SH2).
 *
 * @param cell the cell: the identifier of its row, a colon, the identifier of its column
 * @param answer the answer
 */
public record SheetCalculatedCellView(@NotNull String cell, @NotNull SheetCalculatedAnswerView answer) {
}
