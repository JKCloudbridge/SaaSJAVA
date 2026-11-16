package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * A place in the uploaded file, as a person reads it in the spreadsheet program (SH1B): a row number and a column
 * letter. It is a position and carries no text of the file.
 *
 * <p>The file's address and the sheet's are not the same when rows above the table (a title, the row that names the
 * columns) did not become rows of the sheet (SH5.1, ADR-0263): {@code sheetRow} is the row number the sheet's grid and
 * formula bar show. The column letter is the same in both, because the table always starts at the file's column A.
 *
 * @param row the row number in the file, counted from one
 * @param column the column letters, such as {@code B} or {@code AA}, the same in the file and on the sheet
 * @param sheetRow the row number on the sheet, counted from one, or null when the place is not a row of the sheet
 *         (above the table, or a sheet whose filler adds the rows)
 */
public record SheetImportPlaceView(@NotNull Integer row, @NotNull String column, Integer sheetRow) {
}
