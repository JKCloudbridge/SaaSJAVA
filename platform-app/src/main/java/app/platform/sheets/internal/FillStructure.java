package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetGrid.Cell;
import app.platform.sheets.internal.SheetGrid.Column;
import app.platform.sheets.internal.SheetGrid.Line;
import app.platform.sheets.internal.SheetGrid.Merge;
import app.platform.sheets.internal.SheetGrid.RowsMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a fill-in may be made of, read from the version of the sheet it is pinned to (FF1, ADR-0192): the columns it
 * knows, the rows the sheet names, which cells a person may type into, and whether the filler may add rows. It is a
 * pure function of the grid, so the rules of a fill-in are the sheet's own and nothing about them is stored twice.
 *
 * <p>A body cell is an <em>input</em> cell unless the sheet fixes it: it holds text and no kind, or says it is fixed
 * (ADR-0173 section 1), or it is covered by a merged area without being its top left cell (a merge keeps content on
 * that one cell). A row the filler added has no stored cells in the sheet, so every cell of it is an input cell. A
 * header row is never a row of a fill-in.
 */
final class FillStructure {

    private final SheetGrid grid;
    private final Map<String, Column> columns = new LinkedHashMap<>();
    private final List<String> namedRows = new ArrayList<>();
    private final Set<String> namedRowSet = new HashSet<>();
    private final Set<String> reserved = new HashSet<>();
    private final Set<String> notInput = new HashSet<>();

    FillStructure(SheetGrid grid) {
        this.grid = grid;
        for (Column column : grid.columns()) {
            columns.put(column.id(), column);
            reserved.add(column.id());
        }
        Map<String, Integer> positions = new HashMap<>();
        List<String> order = new ArrayList<>();
        Set<String> header = new HashSet<>();
        for (Line line : grid.headerRows()) {
            positions.put(line.id(), order.size());
            order.add(line.id());
            header.add(line.id());
            reserved.add(line.id());
        }
        for (Line line : grid.rows()) {
            positions.put(line.id(), order.size());
            order.add(line.id());
            namedRows.add(line.id());
            namedRowSet.add(line.id());
            reserved.add(line.id());
        }
        for (Map.Entry<String, Cell> entry : grid.cells().entrySet()) {
            String rowId = entry.getKey().substring(0, entry.getKey().indexOf(':'));
            if (!header.contains(rowId) && SheetGridValidator.isFixed(entry.getValue(), false)) {
                notInput.add(entry.getKey());
            }
        }
        Map<String, Integer> columnPositions = new HashMap<>();
        for (int i = 0; i < grid.columns().size(); i++) {
            columnPositions.put(grid.columns().get(i).id(), i);
        }
        for (Merge merge : grid.merges()) {
            cover(merge, positions, columnPositions, order, header);
        }
    }

    /** The cells a merged area covers, except its top left one, hold nothing and cannot be typed into. */
    private void cover(Merge merge, Map<String, Integer> positions, Map<String, Integer> columnPositions,
            List<String> order, Set<String> header) {
        int fromColon = merge.from().indexOf(':');
        int toColon = merge.to().indexOf(':');
        Integer top = positions.get(merge.from().substring(0, fromColon));
        Integer bottom = positions.get(merge.to().substring(0, toColon));
        Integer left = columnPositions.get(merge.from().substring(fromColon + 1));
        Integer right = columnPositions.get(merge.to().substring(toColon + 1));
        if (top == null || bottom == null || left == null || right == null || header.contains(order.get(top))) {
            return;
        }
        for (int r = top; r <= bottom; r++) {
            for (int c = left; c <= right; c++) {
                if (r != top || c != left) {
                    notInput.add(order.get(r) + ":" + grid.columns().get(c).id());
                }
            }
        }
    }

    /** The column with this identifier, or null. */
    Column column(String id) {
        return columns.get(id);
    }

    /** The columns in the order of the sheet. */
    List<Column> columns() {
        return List.copyOf(columns.values());
    }

    /** The rows the sheet names, in its order; empty for a sheet whose filler adds the rows. */
    List<String> namedRows() {
        return namedRows;
    }

    /** Whether the identifier is a row the sheet names. */
    boolean isNamedRow(String id) {
        return namedRowSet.contains(id);
    }

    /** Whether the identifier belongs to a column, a header row or a named row of the sheet. */
    boolean isReserved(String id) {
        return reserved.contains(id);
    }

    /** Whether the filler may add rows: the sheet is repeatable, or fixed and repeatable. */
    boolean addsRows() {
        return grid.rowsMode() != RowsMode.FIXED;
    }

    /** Whether a person types the first row themselves: the sheet names no rows. */
    boolean fillerAddsAllRows() {
        return grid.dimension() == 1;
    }

    /**
     * Whether a person may type into the cell of this row and column. The row is a named row of the sheet or one the
     * filler added; the column is known.
     */
    boolean isInput(String rowId, String columnId) {
        return !notInput.contains(rowId + ":" + columnId);
    }
}
