package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * What a fill-in may hold in this installation (Functional track FF1, ADR-0192). The server checks every limit again on
 * every save; the screen reads them here so that it can say "no more rows" before the person tries, and holds no number
 * of its own.
 *
 * @param maxBytes the size of the cells document as sent, in bytes
 * @param maxRows the rows of a fill-in, named rows and rows the filler added together
 * @param maxCellChars the characters of the text of a cell
 */
public record SheetFillLimitsView(@NotNull Integer maxBytes, @NotNull Integer maxRows,
        @NotNull Integer maxCellChars) {
}
