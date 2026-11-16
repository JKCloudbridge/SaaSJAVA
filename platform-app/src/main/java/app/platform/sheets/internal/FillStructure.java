package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetDocument.Input;
import app.platform.sheets.internal.SheetDocument.InputClass;
import app.platform.sheets.internal.SheetDocument.Tab;
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
import tools.jackson.databind.JsonNode;

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

    /**
     * What a cell holds (SH5): the kinds of a column, and the kinds a cell may state for itself: an attachment, and a
     * yes or no answer with an optional reason (SH5.1, ADR-0263).
     */
    enum CellType {
        TEXT, NUMBER, DATE, PICKLIST, BOOLEAN, ATTACHMENT, BOOLEAN_REASON
    }

    /**
     * What one cell is: its own statement in the sheet's {@code inputs}, else its column's (SH5, ADR-0251).
     *
     * @param type the kind of value
     * @param required whether the filler must fill it before the fill-in is sent
     * @param cls who fills it
     * @param picklist the values of a list cell, or null
     * @param maxFiles the most files of an attachment cell (0 for any other kind)
     */
    record Effective(CellType type, boolean required, InputClass cls, java.util.List<String> picklist, int maxFiles) {
    }

    private final SheetGrid grid;
    private final Tab tab;
    private final Map<String, Column> columns = new LinkedHashMap<>();
    private final List<String> namedRows = new ArrayList<>();
    private final Set<String> namedRowSet = new HashSet<>();
    private final Set<String> reserved = new HashSet<>();
    private final Set<String> notInput = new HashSet<>();

    /**
     * The structure of the sheet a stored version holds: its first tab for format version 2 (a template holds one tab
     * for now), else the grid of format version 1.
     */
    static FillStructure of(JsonNode stored) {
        if (SheetGridValidator.isFormatTwo(stored)) {
            Tab first = SheetDocumentReader.read(stored).sheets().get(0);
            return new FillStructure(first.body(), first);
        }
        return new FillStructure(SheetGridReader.read(stored));
    }

    /** The tab of a sheet in format version 2, or null for format version 1. */
    Tab tab() {
        return tab;
    }

    /** The body of the sheet. */
    SheetGrid grid() {
        return grid;
    }

    /** The structure of a fill-in on a sheet in format version 1. */
    FillStructure(SheetGrid grid) {
        this(grid, null);
    }

    /**
     * The structure of a fill-in on a sheet of format version 2 when {@code tab} is given: a cell of a calculated
     * column is never an input cell, and a cell may state its own type, requirement and class.
     */
    FillStructure(SheetGrid grid, Tab tab) {
        this.grid = grid;
        this.tab = tab;
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
        if (tab != null) {
            // A cell with a formula is already fixed in the body; a calculated column is never typed into (SH2).
            for (String column : tab.columnFormulas().keySet()) {
                calculated.add(column);
            }
        }
    }

    private final Set<String> calculated = new HashSet<>();

    /** Whether the fill-in is on a sheet in format version 2 (it is then stored in format 3). */
    boolean isFormatThree() {
        return tab != null;
    }

    /** The key of the sheet, or null for format version 1. */
    String sheetKey() {
        return tab == null ? null : tab.key();
    }

    /** Whether the sheet takes fill-ins at all (an information tab does not). */
    boolean fillable() {
        return tab == null || tab.fillable();
    }

    /** Whether the column holds a formula drawn at every row: nobody types into it. */
    boolean isCalculatedColumn(String columnId) {
        return calculated.contains(columnId);
    }

    /**
     * What the cell is. The column must exist. A statement of the cell wins; a missing part is the column's. An
     * attachment cell holds at most {@code maxFiles}, 5 when the sheet says nothing.
     */
    Effective effective(String rowId, String columnId) {
        Column column = columns.get(columnId);
        Input own = tab == null ? null : tab.inputs().get(rowId + ":" + columnId);
        CellType type = CellType.valueOf(column.type().name());
        java.util.List<String> picklist = column.picklist();
        boolean required = column.required();
        InputClass cls = InputClass.FILLER;
        int files = 0;
        if (own != null) {
            if (own.type() != null) {
                type = CellType.valueOf(own.type().toUpperCase(java.util.Locale.ROOT));
            }
            if (own.picklist() != null) {
                picklist = own.picklist();
            }
            required = own.required() || required;
            cls = own.cls() == null ? InputClass.FILLER : own.cls();
            if (type == CellType.ATTACHMENT) {
                files = own.maxFiles() == null ? SheetDocument.DEFAULT_MAX_FILES : own.maxFiles();
            }
        } else if (type == CellType.ATTACHMENT) {
            files = SheetDocument.DEFAULT_MAX_FILES;
        }
        return new Effective(type, required, cls, picklist, files);
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
        return !calculated.contains(columnId) && !notInput.contains(rowId + ":" + columnId);
    }
}
