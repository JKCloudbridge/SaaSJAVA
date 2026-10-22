package app.platform.inspection.internal;

import app.platform.sheets.SheetLifecycle.Layout;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The place a comment points at (FA5, ADR-0191, ADR-0173 section 1): the whole template, one row, one column or
 * one cell of the version that was read. A place is the stable identifiers of the grid, never a position, so it is
 * the same place in a later version for as long as the row or column is still there.
 *
 * <p>Rows (header rows included) and columns share one namespace of identifiers in a grid, and the pair of a row and a
 * column is a cell whether or not the cell holds anything (an input cell is not stored in the grid). Pure logic.
 *
 * @param rowId the row, or null
 * @param columnId the column, or null
 */
record ChangeAnchor(String rowId, String columnId) {

    /** The shape of an identifier, which the grid's own rule fixes (ADR-0173). */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final int LABEL_MOST = 200;

    /** What a place is. */
    enum Kind {
        /** The template as a whole. */
        TEMPLATE,
        /** One row. */
        ROW,
        /** One column. */
        COLUMN,
        /** One cell: a row and a column. */
        CELL
    }

    /** The place with no row and no column: the template as a whole. */
    static final ChangeAnchor WHOLE = new ChangeAnchor(null, null);

    ChangeAnchor {
        rowId = blank(rowId);
        columnId = blank(columnId);
    }

    Kind kind() {
        if (rowId == null && columnId == null) {
            return Kind.TEMPLATE;
        }
        if (columnId == null) {
            return Kind.ROW;
        }
        return rowId == null ? Kind.COLUMN : Kind.CELL;
    }

    /** Words for what is wrong with the shape of the identifiers, or empty when they are well formed. */
    Optional<String> shapeProblem() {
        boolean badRow = rowId != null && !ID.matcher(rowId).matches();
        boolean badColumn = columnId != null && !ID.matcher(columnId).matches();
        if (badRow || badColumn) {
            return Optional.of("The place is not one of this sheet.");
        }
        return Optional.empty();
    }

    /** Whether the row and the column, those that are named, are in the version. */
    boolean presentIn(Layout layout) {
        return (rowId == null || layout.rows().containsKey(rowId))
                && (columnId == null || layout.columns().containsKey(columnId));
    }

    /**
     * The words that name the place as it was in the version, kept with the comment so that it can be read when the
     * place has gone: the column and then the row of a cell, the row, the column, or nothing for the whole template.
     * Cut to the most a label may have.
     */
    String label(Layout layout) {
        String row = rowId == null ? "" : layout.rows().getOrDefault(rowId, "");
        String column = columnId == null ? "" : layout.columns().getOrDefault(columnId, "");
        String joined = switch (kind()) {
            case TEMPLATE -> "";
            case ROW -> row;
            case COLUMN -> column;
            case CELL -> column.isEmpty() ? row : row.isEmpty() ? column : column + " / " + row;
        };
        return joined.length() <= LABEL_MOST ? joined : joined.substring(0, LABEL_MOST);
    }

    private static String blank(String id) {
        return id == null || id.isBlank() ? null : id.strip();
    }
}
