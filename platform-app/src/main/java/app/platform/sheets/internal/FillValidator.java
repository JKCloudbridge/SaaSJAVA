package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetGrid.Column;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks the cells of a fill-in completely, in a fixed order, and says every place that is wrong in fixed words (FF1,
 * ADR-0192). It is the pipeline of the grid validator again ({@link SheetGridValidator}): <strong>the first stage with
 * a problem answers alone</strong>, so a document that is too large is never parsed and one that is not JSON is never
 * measured for its shape.
 *
 * <ol>
 *   <li>Size, as the bytes that arrived, before anything is parsed; then nesting, read from the raw bytes.</li>
 *   <li>JSON, with a repeated key refused.</li>
 *   <li>Shape: the format number, a list of rows, each with an identifier and its cells, a value that is text, yes or
 *       no, or nothing.</li>
 *   <li>Limits that are settings: the rows, the length of a text.</li>
 *   <li>Text: no markup and no control characters.</li>
 *   <li>Rows: an identifier used once; a row of the sheet or a row the filler added, and the filler may add rows only
 *       where the sheet allows it.</li>
 *   <li>Cells: the column exists in the version the fill-in is pinned to, the cell is an input cell, and the value fits
 *       the type of the column.</li>
 * </ol>
 *
 * <p>An accepted document is returned in its <em>canonical</em> form: nothing is stored for a cell with no value and
 * the rows of the sheet that hold no value are left out (the document is sparse), the rows of the sheet come first in
 * the sheet's order and then the rows the filler added in the order given, and every object's keys are in order, so the
 * same fill-in always has the same text and the same hash.
 *
 * <p>Whether the fill-in is <em>complete</em> (every required value there) is a separate question, asked when it is
 * sent for review ({@link #completeness}); a draft may be saved half done. A problem names a place made of positions
 * and identifiers only, and its words never repeat a typed text or a key: the cells are the organization's text
 * (ADR-0012).
 */
final class FillValidator {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final int MOST_PROBLEMS = 20;
    private static final String ROOT = "cells";
    private static final JsonMapper WRITER = JsonMapper.builder().build();

    /** The canonical text of a fill-in with nothing in it. */
    static final String EMPTY = "{\"formatVersion\":1,\"rows\":[]}";

    /**
     * One problem: a stable code (for tests and metrics), the place and the words shown to the person.
     *
     * @param code a lower case code
     * @param place where, for example {@code cells.rows[2].cells.colA}
     * @param message what is wrong
     */
    record Problem(String code, String place, String message) {
    }

    /**
     * An accepted document.
     *
     * @param doc the normalised model
     * @param canonical the canonical JSON text
     * @param hash the SHA-256 of the canonical text as UTF-8
     * @param bytes the length of the canonical text as UTF-8
     */
    record Checked(FillDoc doc, String canonical, byte[] hash, int bytes) {

        @Override
        public String toString() {
            return "Checked[redacted]";
        }
    }

    /**
     * The outcome: an accepted document, or the problems of the first stage that found any.
     *
     * @param checked the accepted document, or null
     * @param problems the problems, empty when accepted
     */
    record Result(Checked checked, List<Problem> problems) {

        boolean accepted() {
            return checked != null;
        }
    }

    /** A row as it arrived, before the sheet is asked about it. */
    private record RawRow(String id, Map<String, Object> cells) {
    }

    private final FillProperties properties;
    private final int maxCellChars;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    FillValidator(FillProperties properties, SheetProperties.Limits limits) {
        this.properties = properties;
        this.maxCellChars = limits.maxCellChars();
    }

    /** Checks the bytes of a cells document as they arrived, against the sheet version the fill-in is pinned to. */
    Result check(byte[] raw, FillStructure structure) {
        if (raw.length > properties.maxBytes()) {
            return rejected(new Problem("too_large", ROOT, "The fill-in is larger than the allowed size of "
                    + properties.maxBytes() + " bytes."));
        }
        if (SheetGridValidator.nestingOf(raw) > properties.maxDepth()) {
            return rejected(new Problem("too_deep", ROOT, "The fill-in is nested deeper than the allowed "
                    + properties.maxDepth() + " levels."));
        }
        JsonNode document;
        try {
            document = json.readTree(raw);
        } catch (RuntimeException e) {
            // The parser's own message quotes the text it choked on: say nothing of it.
            return rejected(new Problem("not_json", ROOT, "The fill-in is not valid JSON, or it repeats a key."));
        }
        if (document == null || document.isMissingNode() || document.isNull()) {
            return rejected(new Problem("not_json", ROOT, "The fill-in is not valid JSON, or it repeats a key."));
        }
        List<Problem> shape = new ArrayList<>();
        List<RawRow> rows = shape(document, shape);
        if (!shape.isEmpty()) {
            return capped(shape);
        }
        List<Supplier<List<Problem>>> stages = List.of(() -> limitProblems(rows), () -> textProblems(rows),
                () -> rowProblems(rows, structure), () -> cellProblems(rows, structure));
        for (Supplier<List<Problem>> next : stages) {
            List<Problem> stage = next.get();
            if (!stage.isEmpty()) {
                return capped(stage);
            }
        }
        FillDoc normal = normalize(rows, structure);
        String canonical = write(normal);
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > properties.maxBytes()) {
            return rejected(new Problem("too_large", ROOT, "The fill-in is larger than the allowed size of "
                    + properties.maxBytes() + " bytes."));
        }
        return new Result(new Checked(normal, canonical, SheetGridWriter.sha256(bytes), bytes.length), List.of());
    }

    /**
     * Reads a document this class wrote earlier. It trusts the shape: what is stored passed {@link #check} when it was
     * saved, so a mistake here is an internal one and fails loudly.
     */
    FillDoc read(String stored) {
        JsonNode root = WRITER.readTree(stored);
        List<FillDoc.Row> rows = new ArrayList<>();
        for (JsonNode row : root.path("rows")) {
            Map<String, Object> cells = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> cell : row.path("cells").properties()) {
                cells.put(cell.getKey(), cell.getValue().isBoolean() ? (Object) cell.getValue().asBoolean()
                        : cell.getValue().asString());
            }
            rows.add(new FillDoc.Row(row.path("id").asString(), cells));
        }
        return new FillDoc(rows);
    }

    /** The canonical text of a document. */
    static String write(FillDoc doc) {
        Map<String, Object> root = new TreeMap<>();
        root.put("formatVersion", FillDoc.FORMAT_VERSION);
        List<Object> rows = new ArrayList<>();
        for (FillDoc.Row row : doc.rows()) {
            Map<String, Object> one = new TreeMap<>();
            one.put("id", row.id());
            one.put("cells", new TreeMap<>(row.cells()));
            rows.add(one);
        }
        root.put("rows", rows);
        return WRITER.writeValueAsString(root);
    }

    /**
     * What is still to fill in before the fill-in can be sent for review: a sheet whose filler adds the rows needs at
     * least one, and every required column needs a value in every row, the rows of the sheet that hold nothing
     * included. A cell the sheet fixes is never asked for. At most twenty problems.
     */
    List<Problem> completeness(FillStructure structure, FillDoc doc) {
        List<Problem> out = new ArrayList<>();
        if (structure.fillerAddsAllRows() && doc.rows().isEmpty()) {
            out.add(new Problem("no_rows", ROOT + ".rows",
                    "A sheet whose filler adds the rows needs at least one row."));
            return out;
        }
        Map<String, FillDoc.Row> byId = new LinkedHashMap<>();
        doc.rows().forEach(row -> byId.put(row.id(), row));
        for (Column column : structure.columns()) {
            if (!column.required()) {
                continue;
            }
            for (String rowId : structure.namedRows()) {
                FillDoc.Row row = byId.get(rowId);
                if (structure.isInput(rowId, column.id()) && (row == null || !row.cells().containsKey(column.id()))) {
                    out.add(missing(ROOT + ".rows." + rowId + ".cells." + column.id()));
                }
            }
            for (int i = 0; i < doc.rows().size(); i++) {
                FillDoc.Row row = doc.rows().get(i);
                if (!structure.isNamedRow(row.id()) && !row.cells().containsKey(column.id())) {
                    out.add(missing(ROOT + ".rows[" + i + "].cells." + column.id()));
                }
            }
            if (out.size() >= MOST_PROBLEMS) {
                return out.subList(0, MOST_PROBLEMS);
            }
        }
        return out;
    }

    private static Problem missing(String place) {
        return new Problem("required", place, "A value that is required is missing.");
    }

    // ---- shape ----

    /** Reads the rows of a document, saying every place whose shape is wrong. */
    private List<RawRow> shape(JsonNode root, List<Problem> out) {
        List<RawRow> rows = new ArrayList<>();
        if (!root.isObject()) {
            out.add(new Problem("shape", ROOT, "This has the wrong kind of value."));
            return rows;
        }
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            if (!"formatVersion".equals(entry.getKey()) && !"rows".equals(entry.getKey())) {
                out.add(new Problem("shape", ROOT + ".…", "This is not allowed here."));
            }
        }
        JsonNode format = root.path("formatVersion");
        if (!format.isInt() || format.asInt() != FillDoc.FORMAT_VERSION) {
            out.add(new Problem("format", ROOT + ".formatVersion", "The format of the fill-in is not supported."));
        }
        JsonNode list = root.path("rows");
        if (!list.isArray()) {
            out.add(new Problem("shape", ROOT + ".rows", "This is needed here and is missing."));
            return rows;
        }
        for (int i = 0; i < list.size(); i++) {
            shapeOfRow(list.get(i), i, rows, out);
        }
        return rows;
    }

    private void shapeOfRow(JsonNode row, int index, List<RawRow> rows, List<Problem> out) {
        String place = ROOT + ".rows[" + index + "]";
        if (!row.isObject()) {
            out.add(new Problem("shape", place, "This has the wrong kind of value."));
            return;
        }
        for (Map.Entry<String, JsonNode> entry : row.properties()) {
            if (!"id".equals(entry.getKey()) && !"cells".equals(entry.getKey())) {
                out.add(new Problem("shape", place + ".…", "This is not allowed here."));
            }
        }
        JsonNode id = row.path("id");
        if (!id.isString() || !ID.matcher(id.asString()).matches()) {
            out.add(new Problem("shape", place + ".id", "An identifier has an invalid shape."));
        }
        JsonNode cells = row.path("cells");
        if (!cells.isObject()) {
            out.add(new Problem("shape", place + ".cells", "This is needed here and is missing."));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> cell : cells.properties()) {
            if (!ID.matcher(cell.getKey()).matches()) {
                out.add(new Problem("shape", place + ".cells.…", "An identifier has an invalid shape."));
                continue;
            }
            JsonNode value = cell.getValue();
            if (value.isString()) {
                values.put(cell.getKey(), value.asString());
            } else if (value.isBoolean()) {
                values.put(cell.getKey(), value.asBoolean());
            } else if (value.isNull()) {
                values.put(cell.getKey(), null);
            } else {
                out.add(new Problem("shape", place + ".cells." + cell.getKey(), "This has the wrong kind of value."));
            }
        }
        if (id.isString()) {
            rows.add(new RawRow(id.asString(), values));
        }
    }

    // ---- limits and text ----

    private List<Problem> limitProblems(List<RawRow> rows) {
        List<Problem> out = new ArrayList<>();
        if (rows.size() > properties.maxRows()) {
            out.add(new Problem("too_many_rows", ROOT + ".rows", "There are more rows than the allowed "
                    + properties.maxRows() + "."));
        }
        for (int i = 0; i < rows.size(); i++) {
            for (Map.Entry<String, Object> cell : rows.get(i).cells().entrySet()) {
                if (cell.getValue() instanceof String text && text.length() > maxCellChars) {
                    out.add(new Problem("text_too_long", cellPlace(i, cell.getKey()), "A text is longer than the "
                            + "allowed " + maxCellChars + " characters."));
                }
            }
        }
        return out;
    }

    private List<Problem> textProblems(List<RawRow> rows) {
        List<Problem> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            for (Map.Entry<String, Object> cell : rows.get(i).cells().entrySet()) {
                if (cell.getValue() instanceof String text && CellValues.unsafe(text)) {
                    out.add(new Problem("unsafe_text", cellPlace(i, cell.getKey()), CellValues.TEXT_UNSAFE));
                }
            }
        }
        return out;
    }

    // ---- rows and cells ----

    private List<Problem> rowProblems(List<RawRow> rows, FillStructure structure) {
        List<Problem> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            String id = rows.get(i).id();
            String place = ROOT + ".rows[" + i + "]";
            if (!seen.add(id)) {
                out.add(new Problem("duplicate_row", place + ".id", "An identifier is used twice."));
            } else if (!structure.isNamedRow(id)) {
                if (structure.isReserved(id)) {
                    out.add(new Problem("row_id_reserved", place + ".id",
                            "An identifier belongs to a column or a header row of the sheet."));
                } else if (!structure.addsRows()) {
                    out.add(new Problem("rows_fixed", place,
                            "The rows of this sheet are fixed: a row cannot be added."));
                }
            }
        }
        return out;
    }

    private List<Problem> cellProblems(List<RawRow> rows, FillStructure structure) {
        List<Problem> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            RawRow row = rows.get(i);
            for (Map.Entry<String, Object> cell : row.cells().entrySet()) {
                String place = cellPlace(i, cell.getKey());
                Column column = structure.column(cell.getKey());
                if (column == null) {
                    // A key that is not a column of the sheet is whatever the sender typed, even when it looks like an
                    // identifier, and is never repeated.
                    out.add(new Problem("cell_unknown", ROOT + ".rows[" + i + "].cells.…",
                            "A cell points at a column that does not exist."));
                } else if (cell.getValue() == null || cell.getValue() instanceof String text && text.isEmpty()) {
                    continue; // nothing is stored for an empty cell
                } else if (!structure.isInput(row.id(), column.id())) {
                    out.add(new Problem("cell_not_input", place, "This cell cannot be typed into."));
                } else {
                    String bad = CellValues.problem(column, cell.getValue());
                    if (bad != null) {
                        out.add(new Problem("value_type", place, bad));
                    }
                }
            }
        }
        return out;
    }

    // ---- canonical form ----

    private static FillDoc normalize(List<RawRow> rows, FillStructure structure) {
        Map<String, FillDoc.Row> named = new LinkedHashMap<>();
        List<FillDoc.Row> added = new ArrayList<>();
        for (RawRow row : rows) {
            Map<String, Object> cells = new LinkedHashMap<>();
            row.cells().forEach((column, value) -> {
                if (value != null && !(value instanceof String text && text.isEmpty())) {
                    cells.put(column, value);
                }
            });
            FillDoc.Row one = new FillDoc.Row(row.id(), cells);
            if (structure.isNamedRow(row.id())) {
                named.put(row.id(), one);
            } else {
                added.add(one);
            }
        }
        List<FillDoc.Row> ordered = new ArrayList<>();
        for (String id : structure.namedRows()) {
            FillDoc.Row row = named.get(id);
            if (row != null && !row.cells().isEmpty()) {
                ordered.add(row);
            }
        }
        ordered.addAll(added);
        return new FillDoc(ordered);
    }

    private static Result rejected(Problem problem) {
        return new Result(null, List.of(problem));
    }

    private static Result capped(List<Problem> problems) {
        return new Result(null, problems.size() > MOST_PROBLEMS ? problems.subList(0, MOST_PROBLEMS) : problems);
    }

    private static String cellPlace(int row, String column) {
        return ROOT + ".rows[" + row + "].cells." + column;
    }
}
