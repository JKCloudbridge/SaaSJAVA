package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * A visible worksheet of an uploaded workbook, so that the author can choose which one becomes the sheet (SH1B,
 * ADR-0253). The title is the name of the tab: text of the organization, shown to the person and never logged.
 *
 * @param index the number of the worksheet among the visible ones, counted from zero; this is what the choice sends
 * @param title the name of the tab, or absent when it cannot be used as the title of a sheet
 * @param rows the number of rows the worksheet says it covers (what the file claims, to help the choice)
 * @param columns the number of columns the worksheet says it covers
 */
public record SheetImportWorksheetView(@NotNull Integer index, String title, @NotNull Integer rows,
        @NotNull Integer columns) {
}
