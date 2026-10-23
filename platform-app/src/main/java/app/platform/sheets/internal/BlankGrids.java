package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetGrid.Column;
import app.platform.sheets.internal.SheetGrid.ColumnType;
import app.platform.sheets.internal.SheetGrid.RowsMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The grid the first version of a sheet starts from (ADR-0186): one text column and nothing else, in the shape the
 * author chose. The identifier is generated here and never typed (ADR-0173 section 1); the label is the platform's own
 * placeholder, which the author replaces.
 */
final class BlankGrids {

    private BlankGrids() {
    }

    /** A blank grid of the shape. The shape must be a valid one (see {@link SheetGridValidator}). */
    static SheetGrid of(int dimension, RowsMode rowsMode) {
        Column column = new Column("c" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), "Column 1",
                ColumnType.TEXT, false, null, null, null, false);
        return new SheetGrid(dimension, rowsMode, List.of(column), List.of(), List.of(), Map.of(), List.of());
    }
}
