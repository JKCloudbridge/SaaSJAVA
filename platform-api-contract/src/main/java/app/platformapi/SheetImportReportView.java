package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * What a spreadsheet file would give, or gave, as a sheet (UI3B, ADR-0195).
 *
 * @param columns the columns of the grid
 * @param rows the named rows of the grid (0 for a sheet whose filler adds the rows)
 * @param mergedAreas the merged areas that came in
 * @param notes what was left out of the file or came in another form, each with a count
 * @param firstRowLabels whether the first row names the columns in the grid this answer describes: the choice that was
 *         sent, or, when none was sent, the server's own (no when the first row is a title). Absent from an older
 *         server
 * @param firstRowIsTitle whether the first row of the table is a title (one merged area across its whole width), so
 *         that a screen can say so. Absent from an older server (FA14)
 */
public record SheetImportReportView(@NotNull Integer columns, @NotNull Integer rows,
        @NotNull Integer mergedAreas, @NotNull List<SheetImportNoteView> notes, Boolean firstRowLabels,
        Boolean firstRowIsTitle) {

    /** Copies the list, so that it cannot be changed from outside. */
    public SheetImportReportView {
        notes = notes == null ? null : List.copyOf(notes);
    }
}
