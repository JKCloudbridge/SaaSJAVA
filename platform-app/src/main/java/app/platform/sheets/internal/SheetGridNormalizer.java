package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetGrid.Cell;
import app.platform.sheets.internal.SheetGrid.Column;
import app.platform.sheets.internal.SheetGrid.FontSize;
import app.platform.sheets.internal.SheetGrid.Kind;
import app.platform.sheets.internal.SheetGrid.Line;
import app.platform.sheets.internal.SheetGrid.Merge;
import app.platform.sheets.internal.SheetGrid.Style;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Gives a checked grid one form for each meaning (ADR-0186), so that the same grid always has the same canonical text
 * and the same hash, however it was written: a style that changes nothing is dropped, an empty text is nothing, the
 * kind of a cell is stated only where the rules do not already imply it, and a cell that holds nothing is not stored.
 * The merges are put in order (their order means nothing).
 *
 * <p>It runs only on a grid the validator accepted, so it relies on every rule the validator enforces.
 */
final class SheetGridNormalizer {

    private SheetGridNormalizer() {
    }

    static SheetGrid normalize(SheetGrid grid) {
        Set<String> headerIds = new HashSet<>();
        grid.headerRows().forEach(line -> headerIds.add(line.id()));
        Map<String, Cell> cells = new TreeMap<>();
        for (Map.Entry<String, Cell> entry : grid.cells().entrySet()) {
            String rowId = entry.getKey().substring(0, entry.getKey().indexOf(':'));
            Cell cell = cell(entry.getValue(), headerIds.contains(rowId));
            if (cell != null) {
                cells.put(entry.getKey(), cell);
            }
        }
        List<Merge> merges = new ArrayList<>(grid.merges());
        merges.sort(Comparator.comparing(Merge::from).thenComparing(Merge::to));
        return new SheetGrid(grid.dimension(), grid.rowsMode(), grid.columns().stream().map(
                SheetGridNormalizer::column).toList(), grid.headerRows().stream().map(
                SheetGridNormalizer::line).toList(), grid.rows().stream().map(SheetGridNormalizer::line).toList(),
                cells, merges);
    }

    private static Column column(Column column) {
        return new Column(column.id(), column.label(), column.type(), column.required(),
                column.defaultValue() instanceof String text && text.isEmpty() ? null : column.defaultValue(),
                column.picklist(), column.width());
    }

    private static Line line(Line line) {
        return new Line(line.id(), line.label() == null || line.label().isEmpty() ? null : line.label(),
                line.height());
    }

    /** The cell in its one form, or null when it holds nothing and need not be stored. */
    private static Cell cell(Cell cell, boolean header) {
        Object value = cell.value() instanceof String text && text.isEmpty() ? null : cell.value();
        Style style = cell.style() == null || cell.style().isPlain() ? null : new Style(cell.style().bold(),
                cell.style().italic(), cell.style().fontSize() == FontSize.NORMAL ? null : cell.style().fontSize());
        if (SheetGridValidator.isFixed(cell, header)) {
            if (value != null) {
                return new Cell(value, style, null);
            }
            if (header) {
                return style == null ? null : new Cell(null, style, null);
            }
            return new Cell(null, style, Kind.FIXED);
        }
        // An input cell: it carries no style (the validator refused one), and only a preset is worth storing.
        return value == null ? null : new Cell(value, null, Kind.INPUT);
    }
}
