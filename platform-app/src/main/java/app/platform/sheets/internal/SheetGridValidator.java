package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetGrid.Cell;
import app.platform.sheets.internal.SheetGrid.Column;
import app.platform.sheets.internal.SheetGrid.ColumnType;
import app.platform.sheets.internal.SheetGrid.Kind;
import app.platform.sheets.internal.SheetGrid.Line;
import app.platform.sheets.internal.SheetGrid.Merge;
import app.platform.sheets.internal.SheetGrid.RowsMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks a grid completely, in a fixed order, and says every place that is wrong in fixed words (ADR-0173 section 4,
 * ADR-0186). The order is a pipeline like the write pipeline of the record store (ADR-0091): <strong>the first stage
 * with a problem answers alone</strong>, so a document that is too large is never parsed, a document that is not JSON
 * is never measured for its shape, and so on.
 *
 * <ol>
 *   <li>Size, as the bytes that arrived, before anything is parsed; then nesting, read from the raw bytes.</li>
 *   <li>JSON, with a repeated key refused (the parser of a browser keeps the last one and hides the first).</li>
 *   <li>Shape, against the schema file.</li>
 *   <li>Limits that are settings: columns, rows, header rows, merges, picklist values, the length of a text.</li>
 *   <li>Text: no markup and no control characters.</li>
 *   <li>Structure: identifiers unique across columns and rows, picklist lists, the rows mode of the dimension.</li>
 *   <li>Cells: they point at a real row and column, header cells are fixed, a value fits its column, a style sits on
 *       fixed text only.</li>
 *   <li>Merges: inside one zone, written top left to bottom right, more than one cell, never overlapping, and the cells
 *       they cover hold nothing.</li>
 * </ol>
 *
 * <p>An accepted grid is returned in its <em>canonical</em> form, normalised so that two documents that mean the same
 * thing have the same text and the same hash (see {@link SheetGridWriter}). A problem names a place made of schema
 * names, positions and identifiers only, and its words never repeat a typed text, a label or a key: the grid is the
 * organization's text (ADR-0012).
 */
final class SheetGridValidator {

    private static final int MOST_PROBLEMS = 20;

    /**
     * One problem: a stable code (for tests and metrics), the place and the words shown to the person.
     *
     * @param code a lower case code
     * @param place where, for example {@code grid.columns[2].label}
     * @param message what is wrong
     */
    record Problem(String code, String place, String message) {
    }

    /**
     * An accepted grid.
     *
     * @param grid the normalised model
     * @param canonical the canonical JSON text
     * @param hash the SHA-256 of the canonical text as UTF-8
     * @param bytes the length of the canonical text as UTF-8
     */
    record Checked(SheetGrid grid, String canonical, byte[] hash, int bytes) {

        @Override
        public String toString() {
            return "Checked[redacted]";
        }
    }

    /**
     * The outcome: an accepted grid, or the problems of the first stage that found any.
     *
     * @param checked the accepted grid, or null
     * @param problems the problems, empty when accepted
     */
    record Result(Checked checked, List<Problem> problems) {

        boolean accepted() {
            return checked != null;
        }
    }

    private final SheetProperties.Limits limits;
    private final SheetSchemas schemas;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    SheetGridValidator(SheetProperties.Limits limits, SheetSchemas schemas) {
        this.limits = limits;
        this.schemas = schemas;
    }

    /** Checks the bytes of a grid as they arrived. */
    Result check(byte[] raw) {
        if (raw.length > limits.maxBytes()) {
            return rejected(new Problem("too_large", "grid", "The grid is larger than the allowed size of "
                    + limits.maxBytes() + " bytes."));
        }
        if (nestingOf(raw) > limits.maxDepth()) {
            return rejected(new Problem("too_deep", "grid", "The grid is nested deeper than the allowed "
                    + limits.maxDepth() + " levels."));
        }
        JsonNode document;
        try {
            document = json.readTree(raw);
        } catch (RuntimeException e) {
            // The parser's own message quotes the text it choked on: say nothing of it.
            return rejected(new Problem("not_json", "grid", "The grid is not valid JSON, or it repeats a key."));
        }
        if (document == null || document.isMissingNode() || document.isNull()) {
            return rejected(new Problem("not_json", "grid", "The grid is not valid JSON, or it repeats a key."));
        }
        List<Problem> shape = new ArrayList<>();
        for (SheetSchemas.Issue issue : schemas.validate(document, "grid")) {
            shape.add(new Problem("shape", issue.place(), issue.message()));
        }
        if (!shape.isEmpty()) {
            return new Result(null, shape);
        }
        return check(SheetGridReader.read(document));
    }

    /** Checks a model that was built from a document that fits the schema. */
    Result check(SheetGrid grid) {
        Context context = new Context(grid);
        List<Supplier<List<Problem>>> stages = List.of(context::limitProblems, context::textProblems,
                context::structureProblems, context::cellProblems, context::mergeProblems);
        for (Supplier<List<Problem>> next : stages) {
            List<Problem> stage = next.get();
            if (!stage.isEmpty()) {
                return new Result(null, stage.size() > MOST_PROBLEMS ? stage.subList(0, MOST_PROBLEMS) : stage);
            }
        }
        SheetGrid normal = SheetGridNormalizer.normalize(grid);
        String canonical = SheetGridWriter.write(normal);
        byte[] bytes = canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > limits.maxBytes()) {
            return rejected(new Problem("too_large", "grid", "The grid is larger than the allowed size of "
                    + limits.maxBytes() + " bytes."));
        }
        return new Result(new Checked(normal, canonical, SheetGridWriter.sha256(bytes), bytes.length), List.of());
    }

    private static Result rejected(Problem problem) {
        return new Result(null, List.of(problem));
    }

    /** The deepest nesting of the raw JSON text, read without parsing it (strings and escapes are skipped). */
    static int nestingOf(byte[] raw) {
        int depth = 0;
        int deepest = 0;
        boolean inString = false;
        boolean escaped = false;
        for (byte b : raw) {
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (b == '\\') {
                    escaped = true;
                } else if (b == '"') {
                    inString = false;
                }
            } else if (b == '"') {
                inString = true;
            } else if (b == '{' || b == '[') {
                depth++;
                deepest = Math.max(deepest, depth);
            } else if (b == '}' || b == ']') {
                depth--;
            }
        }
        return deepest;
    }

    /** The stages that need the model, sharing what they look up. */
    private final class Context {

        private final SheetGrid grid;
        private final Map<String, Integer> columnPositions = new HashMap<>();
        private final Map<String, Integer> rowPositions = new HashMap<>();
        private final Set<String> headerIds = new HashSet<>();
        private final List<String> rowOrder = new ArrayList<>();

        Context(SheetGrid grid) {
            this.grid = grid;
            for (int i = 0; i < grid.columns().size(); i++) {
                columnPositions.putIfAbsent(grid.columns().get(i).id(), i);
            }
            for (Line line : grid.headerRows()) {
                rowPositions.putIfAbsent(line.id(), rowOrder.size());
                headerIds.add(line.id());
                rowOrder.add(line.id());
            }
            for (Line line : grid.rows()) {
                rowPositions.putIfAbsent(line.id(), rowOrder.size());
                rowOrder.add(line.id());
            }
        }

        // ---- limits ----

        List<Problem> limitProblems() {
            List<Problem> out = new ArrayList<>();
            if (grid.columns().size() > limits.maxColumns()) {
                out.add(new Problem("too_many_columns", "grid.columns", "There are more columns than the allowed "
                        + limits.maxColumns() + "."));
            }
            if (grid.rows().size() > limits.maxRows()) {
                out.add(new Problem("too_many_rows", "grid.rows", "There are more rows than the allowed "
                        + limits.maxRows() + "."));
            }
            if (grid.headerRows().size() > limits.maxHeaderRows()) {
                out.add(new Problem("too_many_header_rows", "grid.headerRows", "There are more header rows than the "
                        + "allowed " + limits.maxHeaderRows() + "."));
            }
            if (grid.merges().size() > limits.maxMerges()) {
                out.add(new Problem("too_many_merges", "grid.merges", "There are more merged areas than the allowed "
                        + limits.maxMerges() + "."));
            }
            for (int i = 0; i < grid.columns().size(); i++) {
                Column column = grid.columns().get(i);
                String place = "grid.columns[" + i + "]";
                if (column.picklist() != null && column.picklist().size() > limits.maxPicklistValues()) {
                    out.add(new Problem("too_many_values", place + ".picklist", "A list has more values than the "
                            + "allowed " + limits.maxPicklistValues() + "."));
                }
                if (column.label().length() > limits.maxLabelChars()) {
                    out.add(new Problem("label_too_long", place + ".label", labelWords()));
                }
                if (column.defaultValue() instanceof String text && text.length() > limits.maxCellChars()) {
                    out.add(new Problem("text_too_long", place + ".default", cellWords()));
                }
            }
            lineLimits("headerRows", grid.headerRows(), out);
            lineLimits("rows", grid.rows(), out);
            for (Map.Entry<String, Cell> entry : sortedCells().entrySet()) {
                if (entry.getValue().value() instanceof String text && text.length() > limits.maxCellChars()) {
                    out.add(new Problem("text_too_long", cellPlace(entry.getKey()), cellWords()));
                }
            }
            return out;
        }

        private void lineLimits(String name, List<Line> lines, List<Problem> out) {
            for (int i = 0; i < lines.size(); i++) {
                String label = lines.get(i).label();
                if (label != null && label.length() > limits.maxLabelChars()) {
                    out.add(new Problem("label_too_long", "grid." + name + "[" + i + "].label", labelWords()));
                }
            }
        }

        private String labelWords() {
            return "A label is longer than the allowed " + limits.maxLabelChars() + " characters.";
        }

        private String cellWords() {
            return "A text is longer than the allowed " + limits.maxCellChars() + " characters.";
        }

        // ---- text ----

        List<Problem> textProblems() {
            List<Problem> out = new ArrayList<>();
            for (int i = 0; i < grid.columns().size(); i++) {
                Column column = grid.columns().get(i);
                String place = "grid.columns[" + i + "]";
                unsafe(column.label(), place + ".label", out);
                if (column.defaultValue() instanceof String text) {
                    unsafe(text, place + ".default", out);
                }
                if (column.picklist() != null) {
                    for (int j = 0; j < column.picklist().size(); j++) {
                        unsafe(column.picklist().get(j), place + ".picklist.values[" + j + "]", out);
                    }
                }
            }
            for (int i = 0; i < grid.headerRows().size(); i++) {
                unsafe(grid.headerRows().get(i).label(), "grid.headerRows[" + i + "].label", out);
            }
            for (int i = 0; i < grid.rows().size(); i++) {
                unsafe(grid.rows().get(i).label(), "grid.rows[" + i + "].label", out);
            }
            for (Map.Entry<String, Cell> entry : sortedCells().entrySet()) {
                if (entry.getValue().value() instanceof String text) {
                    unsafe(text, cellPlace(entry.getKey()) + ".value", out);
                }
            }
            return out;
        }

        private void unsafe(String text, String place, List<Problem> out) {
            if (text != null && CellValues.unsafe(text)) {
                out.add(new Problem("unsafe_text", place, CellValues.TEXT_UNSAFE));
            }
        }

        // ---- structure ----

        List<Problem> structureProblems() {
            List<Problem> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < grid.columns().size(); i++) {
                Column column = grid.columns().get(i);
                if (!seen.add(column.id())) {
                    out.add(new Problem("duplicate_id", "grid.columns[" + i + "].id", "An identifier is used twice."));
                }
                picklistProblems(column, "grid.columns[" + i + "]", out);
            }
            lineIdentifiers("headerRows", grid.headerRows(), seen, out);
            lineIdentifiers("rows", grid.rows(), seen, out);
            if (grid.dimension() == 1) {
                if (grid.rowsMode() != RowsMode.REPEATABLE) {
                    out.add(new Problem("rows_mode", "grid.rowsMode",
                            "A sheet whose filler adds the rows is repeatable."));
                }
                if (!grid.rows().isEmpty()) {
                    out.add(new Problem("rows_mode", "grid.rows", "A sheet whose filler adds the rows names no rows."));
                }
            } else if (grid.rowsMode() == RowsMode.REPEATABLE) {
                out.add(new Problem("rows_mode", "grid.rowsMode",
                        "A sheet that names its rows is fixed, or fixed and repeatable."));
            }
            return out;
        }

        private void lineIdentifiers(String name, List<Line> lines, Set<String> seen, List<Problem> out) {
            for (int i = 0; i < lines.size(); i++) {
                if (!seen.add(lines.get(i).id())) {
                    out.add(new Problem("duplicate_id", "grid." + name + "[" + i + "].id",
                            "An identifier is used twice."));
                }
            }
        }

        private void picklistProblems(Column column, String place, List<Problem> out) {
            if (column.type() == ColumnType.PICKLIST) {
                if (column.picklist() == null || column.picklist().isEmpty()) {
                    out.add(new Problem("picklist_missing", place, "A list column needs its values."));
                    return;
                }
                Set<String> values = new HashSet<>();
                for (int j = 0; j < column.picklist().size(); j++) {
                    String value = column.picklist().get(j);
                    if (value.isBlank()) {
                        out.add(new Problem("picklist_blank", place + ".picklist.values[" + j + "]",
                                "A list value cannot be empty."));
                    } else if (!values.add(value)) {
                        out.add(new Problem("picklist_duplicate", place + ".picklist",
                                "A list has the same value twice."));
                    }
                }
            } else if (column.picklist() != null) {
                out.add(new Problem("picklist_unexpected", place, "Only a list column takes values."));
            }
        }

        // ---- cells ----

        List<Problem> cellProblems() {
            List<Problem> out = new ArrayList<>();
            for (int i = 0; i < grid.columns().size(); i++) {
                Column column = grid.columns().get(i);
                if (column.defaultValue() != null) {
                    String bad = CellValues.problem(column, column.defaultValue());
                    if (bad != null) {
                        out.add(new Problem("default_type", "grid.columns[" + i + "].default", bad));
                    }
                }
            }
            for (Map.Entry<String, Cell> entry : sortedCells().entrySet()) {
                String place = cellPlace(entry.getKey());
                int colon = entry.getKey().indexOf(':');
                String rowId = entry.getKey().substring(0, colon);
                String columnId = entry.getKey().substring(colon + 1);
                Integer columnAt = columnPositions.get(columnId);
                if (!rowPositions.containsKey(rowId) || columnAt == null) {
                    out.add(new Problem("cell_unknown", place,
                            "A cell points at a row or column that does not exist."));
                    continue;
                }
                Cell cell = entry.getValue();
                boolean header = headerIds.contains(rowId);
                if (header && cell.kind() == Kind.INPUT) {
                    out.add(new Problem("header_input", place, "A header cell cannot be typed into."));
                    continue;
                }
                boolean fixed = isFixed(cell, header);
                if (fixed && cell.value() instanceof Boolean) {
                    out.add(new Problem("fixed_value", place, "A fixed cell holds text."));
                } else if (!fixed && cell.value() != null) {
                    String bad = CellValues.problem(grid.columns().get(columnAt), cell.value());
                    if (bad != null) {
                        out.add(new Problem("value_type", place, bad));
                    }
                }
                if (!fixed && cell.style() != null && !cell.style().isPlain()) {
                    out.add(new Problem("style_on_input", place,
                            "Only fixed text can be bold, italic or a size. Type the text first."));
                }
            }
            return out;
        }

        // ---- merges ----

        /** A merge in positions: rows and columns, both ends included. */
        private record Rect(int top, int left, int bottom, int right) {

            boolean overlaps(Rect other) {
                return top <= other.bottom && bottom >= other.top && left <= other.right && right >= other.left;
            }

            boolean contains(int row, int column) {
                return row >= top && row <= bottom && column >= left && column <= right;
            }

            long area() {
                return (long) (bottom - top + 1) * (right - left + 1);
            }
        }

        List<Problem> mergeProblems() {
            List<Problem> out = new ArrayList<>();
            List<Rect> rects = new ArrayList<>();
            for (int i = 0; i < grid.merges().size(); i++) {
                Merge merge = grid.merges().get(i);
                String place = "grid.merges[" + i + "]";
                Rect rect = rectOf(merge, place, out);
                if (rect == null) {
                    continue;
                }
                for (Rect other : rects) {
                    if (rect.overlaps(other)) {
                        out.add(new Problem("merge_overlap", place, "Two merged areas cover the same cell."));
                        break;
                    }
                }
                rects.add(rect);
            }
            if (!out.isEmpty()) {
                return out;
            }
            // The areas are separate now, so together they cover no more cells than the grid has.
            for (int i = 0; i < rects.size(); i++) {
                coveredCellsProblems(rects.get(i), "grid.merges[" + i + "]", out);
            }
            return out;
        }

        private Rect rectOf(Merge merge, String place, List<Problem> out) {
            int fromColon = merge.from().indexOf(':');
            int toColon = merge.to().indexOf(':');
            Integer top = rowPositions.get(merge.from().substring(0, fromColon));
            Integer bottom = rowPositions.get(merge.to().substring(0, toColon));
            Integer left = columnPositions.get(merge.from().substring(fromColon + 1));
            Integer right = columnPositions.get(merge.to().substring(toColon + 1));
            if (top == null || bottom == null || left == null || right == null) {
                out.add(new Problem("merge_unknown", place,
                        "A merged area points at a row or column that does not exist."));
                return null;
            }
            boolean topHeader = headerIds.contains(rowOrder.get(top));
            boolean bottomHeader = headerIds.contains(rowOrder.get(bottom));
            if (topHeader != bottomHeader) {
                out.add(new Problem("merge_zones", place, "A merged area cannot join header rows and body rows."));
                return null;
            }
            if (bottom < top || right < left) {
                out.add(new Problem("merge_order", place,
                        "A merged area must run from its top left cell to its bottom right cell."));
                return null;
            }
            if (top.equals(bottom) && left.equals(right)) {
                out.add(new Problem("merge_single", place, "A merged area needs more than one cell."));
                return null;
            }
            return new Rect(top, left, bottom, right);
        }

        /** A merge keeps content on its top left cell only. */
        private void coveredCellsProblems(Rect rect, String place, List<Problem> out) {
            if (rect.area() <= grid.cells().size()) {
                for (int r = rect.top(); r <= rect.bottom(); r++) {
                    for (int c = rect.left(); c <= rect.right(); c++) {
                        if (r == rect.top() && c == rect.left()) {
                            continue;
                        }
                        Cell cell = grid.cells().get(rowOrder.get(r) + ":" + grid.columns().get(c).id());
                        if (cell != null && holdsSomething(cell)) {
                            out.add(new Problem("merge_covered", place,
                                    "A cell covered by a merged area holds something."));
                            return;
                        }
                    }
                }
                return;
            }
            for (Map.Entry<String, Cell> entry : grid.cells().entrySet()) {
                int colon = entry.getKey().indexOf(':');
                Integer row = rowPositions.get(entry.getKey().substring(0, colon));
                Integer column = columnPositions.get(entry.getKey().substring(colon + 1));
                boolean topLeft = row != null && column != null && row == rect.top() && column == rect.left();
                if (row != null && column != null && !topLeft && rect.contains(row, column)
                        && holdsSomething(entry.getValue())) {
                    out.add(new Problem("merge_covered", place, "A cell covered by a merged area holds something."));
                    return;
                }
            }
        }

        private boolean holdsSomething(Cell cell) {
            return cell.value() != null || cell.kind() != null || (cell.style() != null && !cell.style().isPlain());
        }

        // ---- helpers ----

        /** The cells in key order, so that the same document always gives the same problems in the same order. */
        private Map<String, Cell> sortedCells() {
            return new TreeMap<>(grid.cells());
        }

        private String cellPlace(String key) {
            return "grid.cells." + key;
        }
    }

    /** Whether a cell is fixed text: a header cell, a stated fixed cell, or a body cell with a value and no kind. */
    static boolean isFixed(Cell cell, boolean header) {
        return header || cell.kind() == Kind.FIXED || (cell.kind() == null && cell.value() != null);
    }
}
