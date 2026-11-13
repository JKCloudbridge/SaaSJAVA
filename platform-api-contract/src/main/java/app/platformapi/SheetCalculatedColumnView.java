package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The answers of a calculated column, one for each row (SH2).
 *
 * @param column the identifier of the column
 * @param rows one answer for each row
 */
public record SheetCalculatedColumnView(@NotNull String column, @NotNull List<SheetCalculatedAnswerView> rows) {

    /** Copies the list, so that it cannot be changed from outside. */
    public SheetCalculatedColumnView {
        rows = rows == null ? null : List.copyOf(rows);
    }
}
