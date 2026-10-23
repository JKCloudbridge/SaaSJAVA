package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetGrid.Column;
import app.platform.sheets.internal.SheetGrid.ColumnType;
import app.platform.sheets.internal.SheetGrid.Line;
import app.platform.sheets.internal.SheetGrid.RowsMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The grid the first version of a sheet starts from (ADR-0186): ten text columns and, for a sheet that names its
 * own rows, ten empty rows, so that the author sees a sheet to write in and not an empty area; a sheet whose filler
 * adds the rows names none. The identifiers are generated here and never typed (ADR-0173 section 1); the labels are
 * the platform's own placeholders, which the author replaces. A version started from another is a copy of it and
 * never comes here.
 */
final class BlankGrids {

    /** How many columns, and (for a sheet that names its rows) rows, a new sheet starts with. */
    static final int STARTING_SIZE = 10;

    private BlankGrids() {
    }

    /** A blank grid of the shape. The shape must be a valid one (see {@link SheetGridValidator}). */
    static SheetGrid of(int dimension, RowsMode rowsMode) {
        List<Column> columns = new ArrayList<>();
        for (int i = 1; i <= STARTING_SIZE; i++) {
            columns.add(new Column(identifier("c"), "Column " + i, ColumnType.TEXT, false, null, null, null, false));
        }
        List<Line> rows = new ArrayList<>();
        if (dimension == 2) {
            for (int i = 0; i < STARTING_SIZE; i++) {
                rows.add(new Line(identifier("r"), null, null));
            }
        }
        return new SheetGrid(dimension, rowsMode, List.copyOf(columns), List.of(), List.copyOf(rows), Map.of(),
                List.of());
    }

    private static String identifier(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
