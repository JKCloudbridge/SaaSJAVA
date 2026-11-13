package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * What a grid may hold in this installation (Functional track FA3, ADR-0186; SH1A, ADR-0251). The server checks every
 * limit again on every save; the screen reads them here so that it can say "no more columns" before the person tries,
 * and holds no number of its own.
 *
 * @param maxBytes the size of a grid as sent, in bytes
 * @param maxRows the named rows
 * @param maxColumns the columns
 * @param maxHeaderRows the header rows
 * @param maxCellChars the characters of the text of a cell (in format version 2, of a text typed into an input cell)
 * @param maxLabelChars the characters of a label
 * @param maxPicklistValues the values of a picklist column
 * @param maxMerges the merged areas
 * @param maxImportBytes the size of a spreadsheet file that can be uploaded into a sheet (UI3B), in bytes
 * @param maxFixedTextChars the characters of a fixed text in format version 2
 * @param minColumnWidth the narrowest column of format version 2, in pixels
 * @param maxColumnWidth the widest column of format version 2, in pixels
 * @param minRowHeight the shortest row of format version 2, in pixels
 * @param maxRowHeight the tallest row of format version 2, in pixels
 * @param maxSheets the sheets one template may hold in format version 2
 * @param maxFilesPerCell the files one attachment cell may hold
 * @param maxLinkChars the characters of the address of a link
 * @param formatTwoEnabled whether a sheet may be stored in format version 2 here; when false every save of such a
 *        document is refused
 */
public record SheetLimitsView(@NotNull Integer maxBytes, @NotNull Integer maxRows, @NotNull Integer maxColumns,
        @NotNull Integer maxHeaderRows, @NotNull Integer maxCellChars, @NotNull Integer maxLabelChars,
        @NotNull Integer maxPicklistValues, @NotNull Integer maxMerges, @NotNull Integer maxImportBytes,
        @NotNull Integer maxFixedTextChars, @NotNull Integer minColumnWidth, @NotNull Integer maxColumnWidth,
        @NotNull Integer minRowHeight, @NotNull Integer maxRowHeight, @NotNull Integer maxSheets,
        @NotNull Integer maxFilesPerCell, @NotNull Integer maxLinkChars, @NotNull Boolean formatTwoEnabled) {
}
