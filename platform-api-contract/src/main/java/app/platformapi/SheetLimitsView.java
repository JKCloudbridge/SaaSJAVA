package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * What a grid may hold in this installation (Functional track FA3, ADR-0186). The server checks every limit again on
 * every save; the screen reads them here so that it can say "no more columns" before the person tries, and holds no
 * number of its own.
 *
 * @param maxBytes the size of a grid as sent, in bytes
 * @param maxRows the named rows
 * @param maxColumns the columns
 * @param maxHeaderRows the header rows
 * @param maxCellChars the characters of the text of a cell
 * @param maxLabelChars the characters of a label
 * @param maxPicklistValues the values of a picklist column
 * @param maxMerges the merged areas
 */
public record SheetLimitsView(@NotNull Integer maxBytes, @NotNull Integer maxRows, @NotNull Integer maxColumns,
        @NotNull Integer maxHeaderRows, @NotNull Integer maxCellChars, @NotNull Integer maxLabelChars,
        @NotNull Integer maxPicklistValues, @NotNull Integer maxMerges) {
}
