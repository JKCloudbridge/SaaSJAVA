package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The answers of the formulas of one version of a sheet, calculated by the server from the sheet alone, with every
 * input cell empty and one row where the filler adds the rows (SH2, ADR-0252). Answers are computed when asked and are
 * not stored. An error is shown as its code.
 *
 * @param bodyRows how many rows of a sheet where the filler adds all the rows the answers are for
 * @param cells the answer of each formula cell
 * @param columns the answers of each calculated column, one for each row
 */
public record SheetCalculationView(@NotNull Integer bodyRows, @NotNull List<SheetCalculatedCellView> cells,
        @NotNull List<SheetCalculatedColumnView> columns) {

    /** Copies the lists, so that they cannot be changed from outside. */
    public SheetCalculationView {
        cells = cells == null ? null : List.copyOf(cells);
        columns = columns == null ? null : List.copyOf(columns);
    }
}
