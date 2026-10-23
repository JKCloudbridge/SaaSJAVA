package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetGrid.Column;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The reportable values of a fill-in (FF2, ADR-0193): for every column the pinned version flags reportable, the
 * value of
 * every row that holds one, in the typed form a report compares. A pure function of the pinned grid and the cells, so
 * what is copied to the report table is derived and never decided anywhere else, and it can be tested without a
 * database. A cell with no value yields nothing.
 *
 * <p>A picklist column is text (the listed value is compared byte by byte); a number is the digits that were typed,
 * read as an exact decimal; a date is read as a calendar date. The cells passed the fill-in check when they were saved,
 * so the values are known to fit their columns; a value that somehow does not is left out, never guessed.
 */
final class FillValues {

    /** The kind of value, which is also the column of the report table that holds it. */
    enum Kind {
        TEXT, NUMBER, DATE, BOOLEAN
    }

    /**
     * One reportable value. Exactly the member of its kind is set. Everything in it is text of the organization, so it
     * prints as a marker.
     *
     * @param rowId the row of the fill-in
     * @param columnId the column of the pinned version
     * @param kind which of the four members below holds the value
     * @param text the value of a text or picklist column
     * @param number the value of a number column
     * @param date the value of a date column
     * @param flag the value of a yes or no column
     */
    record Value(String rowId, String columnId, Kind kind, String text, BigDecimal number, LocalDate date,
            Boolean flag) {

        @Override
        public String toString() {
            return "Value[redacted]";
        }
    }

    private FillValues() {
    }

    /** The reportable columns of the grid, in the order of the sheet. */
    static List<Column> reportableColumns(SheetGrid grid) {
        return grid.columns().stream().filter(Column::reportable).toList();
    }

    /** The values of the reportable columns of these cells; empty when the version flags none. */
    static List<Value> of(SheetGrid grid, FillDoc doc) {
        List<Column> columns = reportableColumns(grid);
        if (columns.isEmpty()) {
            return List.of();
        }
        List<Value> values = new ArrayList<>();
        for (FillDoc.Row row : doc.rows()) {
            for (Column column : columns) {
                Object cell = row.cells().get(column.id());
                Value value = cell == null ? null : value(row.id(), column, cell);
                if (value != null) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private static Value value(String rowId, Column column, Object cell) {
        try {
            return switch (column.type()) {
                case TEXT, PICKLIST -> cell instanceof String text && !text.isEmpty()
                        ? new Value(rowId, column.id(), Kind.TEXT, text, null, null, null) : null;
                case NUMBER -> cell instanceof String text
                        ? new Value(rowId, column.id(), Kind.NUMBER, null, new BigDecimal(text), null, null) : null;
                case DATE -> cell instanceof String text
                        ? new Value(rowId, column.id(), Kind.DATE, null, null, LocalDate.parse(text), null) : null;
                case BOOLEAN -> cell instanceof Boolean flag
                        ? new Value(rowId, column.id(), Kind.BOOLEAN, null, null, null, flag) : null;
                default -> null;
            };
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return null;
        }
    }
}
