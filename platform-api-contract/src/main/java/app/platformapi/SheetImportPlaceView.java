package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * A place in the uploaded file, as a person reads it in the spreadsheet program (SH1B): a row number and a column
 * letter. It is a position and carries no text of the file.
 *
 * @param row the row number, counted from one
 * @param column the column letters, such as {@code B} or {@code AA}
 */
public record SheetImportPlaceView(@NotNull Integer row, @NotNull String column) {
}
