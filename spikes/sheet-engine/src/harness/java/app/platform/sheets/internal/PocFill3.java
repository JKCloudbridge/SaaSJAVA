package app.platform.sheets.internal;

import app.platform.sheets.internal.PocV2Doc.CellSpec;
import app.platform.sheets.internal.PocV2Doc.Sheet;
import app.platform.sheets.internal.SheetGrid.Column;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * POC 2, WP2. A PROTOTYPE of the fill-in document for a template of format version 2 ("fill format 3"): a list of sheets, each with its
 * rows, a cell that holds text, yes or no, or a LIST OF FILE IDENTIFIERS (an attachment cell). It checks in the staged, message-free
 * style of {@link FillValidator}: size and nesting before parsing, JSON with a repeated key refused, shape, limits, text, sheets and
 * rows, cells (known column, input cell, the class of the cell against the person's role, the effective type = the cell's own type
 * or its column's, the value against the type, and for an attachment only that each identifier belongs to THIS fill-in); the first
 * stage with a problem answers alone and no message repeats anything typed. Completeness (required cells, required attachment) is a
 * separate question asked at Submit. The real {@link CellValues} decides text, number, date and picklist values, unchanged.
 *
 * <p>Format version 1 and 2 fill-ins keep their own reader and writer ({@link FillValidator}); this class is only for the new one.
 */
public final class PocFill3 {

    public enum Role { FILLER, REVIEWER }

    public record Problem(String code, String place) {
    }

    public record Result(String canonical, byte[] hash, List<Problem> problems) {
        public boolean accepted() {
            return canonical != null;
        }
    }

    /** What the fill-in is pinned to: the sheets of one template version. */
    public record Pinned(List<Sheet> sheets) {
        Sheet sheet(String key) {
            for (Sheet s : sheets) {
                if (s.key().equals(key)) {
                    return s;
                }
            }
            return null;
        }
    }

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final int MAX_BYTES = 1_000_000;
    private static final int MAX_ROWS = 1_000;
    private static final int MAX_CELL_CHARS = 1_000;
    private static final int MOST_PROBLEMS = 20;
    private static final JsonMapper STRICT = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final JsonMapper WRITER = JsonMapper.builder().build();

    private PocFill3() {
    }

    // ---------------------------------------------------------------------------------------------------------------

    private record RawRow(String id, Map<String, Object> cells) {
    }

    private record RawSheet(String key, List<RawRow> rows) {
    }

    /** @param files the identifiers of the files of THIS fill-in's header record */
    public static Result check(byte[] raw, Pinned pinned, Set<String> files, Role role) {
        if (raw.length > MAX_BYTES) {
            return fail("too_large", "document");
        }
        if (SheetGridValidator.nestingOf(raw) > 7) {
            return fail("too_deep", "document");
        }
        JsonNode root;
        try {
            root = STRICT.readTree(raw);
        } catch (RuntimeException e) {
            return fail("not_json", "document");
        }
        List<Problem> shape = new ArrayList<>();
        List<RawSheet> sheets = shape(root, shape);
        if (!shape.isEmpty()) {
            return capped(shape);
        }
        List<Supplier<List<Problem>>> stages = List.of(() -> limits(sheets, pinned), () -> text(sheets),
                () -> sheetsAndRows(sheets, pinned), () -> cells(sheets, pinned, files, role));
        for (Supplier<List<Problem>> stage : stages) {
            List<Problem> p = stage.get();
            if (!p.isEmpty()) {
                return capped(p);
            }
        }
        String canonical = write(sheets, pinned);
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            return fail("too_large", "document");
        }
        return new Result(canonical, SheetGridWriter.sha256(bytes), List.of());
    }

    // ---- shape ----

    private static List<RawSheet> shape(JsonNode root, List<Problem> out) {
        List<RawSheet> sheets = new ArrayList<>();
        if (root == null || !root.isObject()) {
            out.add(new Problem("shape", "document"));
            return sheets;
        }
        for (Map.Entry<String, JsonNode> e : root.properties()) {
            if (!e.getKey().equals("formatVersion") && !e.getKey().equals("sheets")) {
                out.add(new Problem("shape", "document.…"));
            }
        }
        if (!root.path("formatVersion").isInt() || root.path("formatVersion").asInt() != 3) {
            out.add(new Problem("format", "formatVersion"));
        }
        JsonNode list = root.path("sheets");
        if (!list.isArray()) {
            out.add(new Problem("shape", "sheets"));
            return sheets;
        }
        for (int i = 0; i < list.size(); i++) {
            JsonNode s = list.get(i);
            String at = "sheets[" + i + "]";
            if (!s.isObject()) {
                out.add(new Problem("shape", at));
                continue;
            }
            for (Map.Entry<String, JsonNode> e : s.properties()) {
                if (!e.getKey().equals("key") && !e.getKey().equals("rows")) {
                    out.add(new Problem("shape", at + ".…"));
                }
            }
            if (!s.path("key").isString() || !ID.matcher(s.path("key").asString()).matches()) {
                out.add(new Problem("shape", at + ".key"));
                continue;
            }
            if (!s.path("rows").isArray()) {
                out.add(new Problem("shape", at + ".rows"));
                continue;
            }
            List<RawRow> rows = new ArrayList<>();
            for (int r = 0; r < s.path("rows").size(); r++) {
                JsonNode row = s.path("rows").get(r);
                String rat = at + ".rows[" + r + "]";
                if (!row.isObject()) {
                    out.add(new Problem("shape", rat));
                    continue;
                }
                for (Map.Entry<String, JsonNode> e : row.properties()) {
                    if (!e.getKey().equals("id") && !e.getKey().equals("cells")) {
                        out.add(new Problem("shape", rat + ".…"));
                    }
                }
                if (!row.path("id").isString() || !ID.matcher(row.path("id").asString()).matches() || !row.path("cells").isObject()) {
                    out.add(new Problem("shape", rat));
                    continue;
                }
                Map<String, Object> cells = new LinkedHashMap<>();
                for (Map.Entry<String, JsonNode> c : row.path("cells").properties()) {
                    JsonNode v = c.getValue();
                    if (!ID.matcher(c.getKey()).matches()) {
                        out.add(new Problem("shape", rat + ".cells.…"));
                    } else if (v.isString()) {
                        cells.put(c.getKey(), v.asString());
                    } else if (v.isBoolean()) {
                        cells.put(c.getKey(), v.asBoolean());
                    } else if (v.isNull()) {
                        cells.put(c.getKey(), null);
                    } else if (v.isArray()) {
                        List<String> ids = new ArrayList<>();
                        boolean ok = true;
                        for (JsonNode item : v) {
                            if (item.isString()) {
                                ids.add(item.asString());
                            } else {
                                ok = false;
                            }
                        }
                        if (ok) {
                            cells.put(c.getKey(), ids);
                        } else {
                            out.add(new Problem("shape", rat + ".cells." + c.getKey()));
                        }
                    } else {
                        out.add(new Problem("shape", rat + ".cells." + c.getKey()));
                    }
                }
                rows.add(new RawRow(row.path("id").asString(), cells));
            }
            sheets.add(new RawSheet(s.path("key").asString(), rows));
        }
        return sheets;
    }

    // ---- limits and text ----

    private static List<Problem> limits(List<RawSheet> sheets, Pinned pinned) {
        List<Problem> out = new ArrayList<>();
        if (sheets.size() > Math.min(PocV2Doc.MAX_SHEETS, pinned.sheets().size())) {
            out.add(new Problem("too_many_sheets", "sheets"));
        }
        for (int s = 0; s < sheets.size(); s++) {
            if (sheets.get(s).rows().size() > MAX_ROWS) {
                out.add(new Problem("too_many_rows", "sheets[" + s + "].rows"));
            }
            for (int r = 0; r < sheets.get(s).rows().size(); r++) {
                for (Map.Entry<String, Object> c : sheets.get(s).rows().get(r).cells().entrySet()) {
                    if (c.getValue() instanceof String t && t.length() > MAX_CELL_CHARS) {
                        out.add(new Problem("text_too_long", place(s, r, c.getKey())));
                    }
                    if (c.getValue() instanceof List<?> l && l.size() > 5) {
                        out.add(new Problem("too_many_files", place(s, r, c.getKey())));
                    }
                }
            }
        }
        return out;
    }

    private static List<Problem> text(List<RawSheet> sheets) {
        List<Problem> out = new ArrayList<>();
        for (int s = 0; s < sheets.size(); s++) {
            for (int r = 0; r < sheets.get(s).rows().size(); r++) {
                for (Map.Entry<String, Object> c : sheets.get(s).rows().get(r).cells().entrySet()) {
                    if (c.getValue() instanceof String t && CellValues.unsafe(t)) {
                        out.add(new Problem("unsafe_text", place(s, r, c.getKey())));
                    }
                    if (c.getValue() instanceof List<?> l) {
                        for (Object item : l) {
                            if (!ID.matcher((String) item).matches()) {
                                out.add(new Problem("file_id_shape", place(s, r, c.getKey())));
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    // ---- sheets and rows ----

    private static List<Problem> sheetsAndRows(List<RawSheet> sheets, Pinned pinned) {
        List<Problem> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int s = 0; s < sheets.size(); s++) {
            RawSheet sheet = sheets.get(s);
            Sheet pinnedSheet = pinned.sheet(sheet.key());
            if (pinnedSheet == null) {
                out.add(new Problem("sheet_unknown", "sheets[" + s + "].key"));
                continue;
            }
            if (!seen.add(sheet.key())) {
                out.add(new Problem("duplicate_sheet", "sheets[" + s + "].key"));
                continue;
            }
            if (!pinnedSheet.fillable() && !sheet.rows().isEmpty()) {
                out.add(new Problem("sheet_not_fillable", "sheets[" + s + "]"));
                continue;
            }
            FillStructure st = new FillStructure(pinnedSheet.grid());
            Set<String> ids = new HashSet<>();
            for (int r = 0; r < sheet.rows().size(); r++) {
                String id = sheet.rows().get(r).id();
                String at = "sheets[" + s + "].rows[" + r + "]";
                if (!ids.add(id)) {
                    out.add(new Problem("duplicate_row", at + ".id"));
                } else if (!st.isNamedRow(id)) {
                    if (st.isReserved(id)) {
                        out.add(new Problem("row_id_reserved", at + ".id"));
                    } else if (!st.addsRows()) {
                        out.add(new Problem("rows_fixed", at));
                    }
                }
            }
        }
        return out;
    }

    // ---- cells ----

    /** The type a cell really has: its own, or its column's. */
    static String effectiveType(Sheet sheet, String rowId, Column column) {
        CellSpec spec = sheet.inputs().get(rowId + ":" + column.id());
        return spec != null && spec.type() != null ? spec.type() : column.type().json();
    }

    private static List<Problem> cells(List<RawSheet> sheets, Pinned pinned, Set<String> files, Role role) {
        List<Problem> out = new ArrayList<>();
        for (int s = 0; s < sheets.size(); s++) {
            RawSheet sheet = sheets.get(s);
            Sheet ps = pinned.sheet(sheet.key());
            FillStructure st = new FillStructure(ps.grid());
            for (int r = 0; r < sheet.rows().size(); r++) {
                RawRow row = sheet.rows().get(r);
                for (Map.Entry<String, Object> cell : row.cells().entrySet()) {
                    Column column = st.column(cell.getKey());
                    Object value = cell.getValue();
                    if (column == null) {
                        out.add(new Problem("cell_unknown", "sheets[" + s + "].rows[" + r + "].cells.…"));
                        continue;
                    }
                    if (value == null || value instanceof String t && t.isEmpty() || value instanceof List<?> l && l.isEmpty()) {
                        continue;
                    }
                    String at = place(s, r, column.id());
                    if (!st.isInput(row.id(), column.id())) {
                        out.add(new Problem("cell_not_input", at));
                        continue;
                    }
                    CellSpec spec = ps.inputs().get(row.id() + ":" + column.id());
                    String cls = spec == null ? "filler" : spec.cls();
                    if (role == Role.FILLER && cls.equals("reviewer") || role == Role.REVIEWER && !cls.equals("reviewer")) {
                        out.add(new Problem("cell_wrong_class", at));
                        continue;
                    }
                    String type = effectiveType(ps, row.id(), column);
                    String bad = valueProblem(type, spec, column, value, files);
                    if (bad != null) {
                        out.add(new Problem(bad, at));
                    }
                }
            }
        }
        return out;
    }

    /** A stable code, or null when the value fits the type. */
    private static String valueProblem(String type, CellSpec spec, Column column, Object value, Set<String> files) {
        switch (type) {
            case "attachment" -> {
                if (!(value instanceof List<?> ids)) {
                    return "value_type";
                }
                int max = spec == null || spec.maxFiles() == 0 ? 5 : spec.maxFiles();
                if (ids.size() > max) {
                    return "too_many_files";
                }
                Set<Object> seen = new HashSet<>();
                for (Object id : ids) {
                    if (!seen.add(id)) {
                        return "file_duplicate";
                    }
                    if (!files.contains(id)) {
                        return "file_not_of_this_fill";
                    }
                }
                return null;
            }
            case "boolean" -> {
                return value instanceof Boolean ? null : "value_type";
            }
            case "picklist" -> {
                List<String> list = spec != null && spec.picklist() != null ? spec.picklist() : column.picklist();
                return value instanceof String t && list != null && list.contains(t) ? null : "value_type";
            }
            case "number", "date", "text" -> {
                if (!(value instanceof String)) {
                    return "value_type";
                }
                SheetGrid.ColumnType ct = SheetGrid.ColumnType.of(type).orElseThrow();
                Column typed = new Column(column.id(), column.label(), ct, false, null, null, null, false);
                return CellValues.problem(typed, value) == null ? null : "value_type";
            }
            default -> {
                return "value_type";
            }
        }
    }

    // ---- completeness, asked at Submit ----

    /** What is still to fill in: a required cell with no value, a required attachment with no file that still exists. */
    public static List<Problem> completeness(String canonical, Pinned pinned, Set<String> files) {
        List<Problem> out = new ArrayList<>();
        JsonNode root = STRICT.readTree(canonical);
        Map<String, Map<String, JsonNode>> byRow = new LinkedHashMap<>();
        for (JsonNode s : root.path("sheets")) {
            for (JsonNode r : s.path("rows")) {
                byRow.put(s.path("key").asString() + "/" + r.path("id").asString(), mapOf(r.path("cells")));
            }
        }
        for (Sheet sheet : pinned.sheets()) {
            FillStructure st = new FillStructure(sheet.grid());
            for (String rowId : st.namedRows()) {
                for (Column column : st.columns()) {
                    if (!st.isInput(rowId, column.id())) {
                        continue;
                    }
                    CellSpec spec = sheet.inputs().get(rowId + ":" + column.id());
                    boolean required = spec != null ? spec.required() : column.required();
                    if (!required) {
                        continue;
                    }
                    JsonNode v = byRow.getOrDefault(sheet.key() + "/" + rowId, Map.of()).get(column.id());
                    boolean present = v != null && !v.isNull();
                    if (present && v.isArray()) {
                        present = false;
                        for (JsonNode id : v) {
                            present |= files.contains(id.asString());
                        }
                    }
                    if (!present) {
                        out.add(new Problem("required", "sheets/" + sheet.key() + "/" + rowId + "/" + column.id()));
                    }
                }
            }
        }
        return out;
    }

    private static Map<String, JsonNode> mapOf(JsonNode cells) {
        Map<String, JsonNode> m = new LinkedHashMap<>();
        cells.properties().forEach(e -> m.put(e.getKey(), e.getValue()));
        return m;
    }

    // ---- canonical form ----

    private static String write(List<RawSheet> sheets, Pinned pinned) {
        Map<String, RawSheet> byKey = new LinkedHashMap<>();
        sheets.forEach(s -> byKey.put(s.key(), s));
        Map<String, Object> root = new TreeMap<>();
        root.put("formatVersion", 3);
        List<Object> list = new ArrayList<>();
        for (Sheet ps : pinned.sheets()) { // the template's order of sheets
            RawSheet in = byKey.get(ps.key());
            if (in == null) {
                continue;
            }
            FillStructure st = new FillStructure(ps.grid());
            Map<String, Map<String, Object>> named = new LinkedHashMap<>();
            List<Map<String, Object>> added = new ArrayList<>();
            for (RawRow row : in.rows()) {
                Map<String, Object> cells = new TreeMap<>();
                row.cells().forEach((k, v) -> {
                    if (v != null && !(v instanceof String t && t.isEmpty()) && !(v instanceof List<?> l && l.isEmpty())) {
                        cells.put(k, v);
                    }
                });
                Map<String, Object> one = new TreeMap<>();
                one.put("id", row.id());
                one.put("cells", cells);
                if (st.isNamedRow(row.id())) {
                    if (!cells.isEmpty()) {
                        named.put(row.id(), one);
                    }
                } else {
                    added.add(one);
                }
            }
            List<Object> rows = new ArrayList<>();
            for (String id : st.namedRows()) {
                if (named.containsKey(id)) {
                    rows.add(named.get(id));
                }
            }
            rows.addAll(added);
            if (!rows.isEmpty()) {
                Map<String, Object> sheet = new TreeMap<>();
                sheet.put("key", ps.key());
                sheet.put("rows", rows);
                list.add(sheet);
            }
        }
        root.put("sheets", list);
        return WRITER.writeValueAsString(root);
    }

    private static String place(int sheet, int row, String column) {
        return "sheets[" + sheet + "].rows[" + row + "].cells." + column;
    }

    private static Result fail(String code, String place) {
        return new Result(null, null, List.of(new Problem(code, place)));
    }

    private static Result capped(List<Problem> p) {
        return new Result(null, null, p.size() > MOST_PROBLEMS ? p.subList(0, MOST_PROBLEMS) : p);
    }

    // ===============================================================================================================
    // the run

    private static final String SECRET = "ZZ-SECRET-ZZ";

    private static Map<String, Object> cellSpec(Object... kv) {
        return GridDocs.map(kv);
    }

    /** The plant-like form: a two-dimensional sheet of 40 named rows with about 50 input cells of mixed kinds. */
    static byte[] plantLikeTemplate() {
        Map<String, Object> doc = GridDocs.map("key", "main", "dimension", 2, "rowsMode", "FIXED");
        List<Map<String, Object>> columns = new ArrayList<>();
        for (int c = 1; c <= 9; c++) {
            columns.add(GridDocs.column("c" + c, "Column " + c, "text"));
        }
        doc.put("columns", columns);
        doc.put("headerRows", new ArrayList<>(List.of(GridDocs.map("id", "h1"))));
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> cells = new LinkedHashMap<>();
        for (int r = 1; r <= 45; r++) {
            rows.add(GridDocs.map("id", "r" + r, "label", "Row " + r));
            cells.put("r" + r + ":c1", GridDocs.map("value", "Fixed " + r));
        }
        doc.put("rows", rows);
        doc.put("cells", cells);
        doc.put("merges", new ArrayList<>());
        Map<String, Object> inputs = new LinkedHashMap<>();
        for (int r = 13; r <= 28; r++) {
            inputs.put("r" + r + ":c6", cellSpec("type", "boolean"));
            inputs.put("r" + r + ":c7", cellSpec("type", "attachment", "required", r % 4 == 0));
        }
        inputs.put("r35:c7", cellSpec("type", "attachment"));
        inputs.put("r41:c7", cellSpec("type", "attachment", "maxFiles", 2));
        for (int r : new int[] {30, 33}) {
            inputs.put("r" + r + ":c5", cellSpec("type", "boolean"));
            inputs.put("r" + r + ":c6", cellSpec("type", "boolean"));
        }
        for (int r = 5; r <= 8; r++) {
            inputs.put("r" + r + ":c4", cellSpec("type", "number", "required", true));
        }
        inputs.put("r3:c3", cellSpec("type", "date"));
        inputs.put("r4:c3", cellSpec("type", "picklist", "picklist", new ArrayList<>(List.of("Pass", "Fail", "Not applicable"))));
        for (int r = 13; r <= 20; r++) {
            inputs.put("r" + r + ":c9", cellSpec("class", "reviewer"));
        }
        doc.put("inputs", inputs);
        doc.put("freeze", GridDocs.map("rows", 1, "cols", 1));
        doc.put("print", GridDocs.map("paper", "A4", "orientation", "PORTRAIT", "fitToWidth", true, "repeatHeaderRows", true));
        return GridDocs.bytes(GridDocs.map("formatVersion", 2, "sheets", new ArrayList<>(List.of(doc))));
    }

    /** A one-dimensional repeatable data sheet (the supplier data sheet's shape): 8 input columns, the filler adds the rows. */
    static Map<String, Object> dataSheet(String key) {
        Map<String, Object> doc = GridDocs.map("key", key, "dimension", 1, "rowsMode", "REPEATABLE");
        List<Map<String, Object>> columns = new ArrayList<>();
        String[] types = {"text", "number", "date", "picklist", "number", "boolean", "text", "number"};
        for (int c = 0; c < types.length; c++) {
            Map<String, Object> col = GridDocs.column("d" + (c + 1), "Column " + (c + 1), types[c]);
            col.put("required", c == 0);
            columns.add(col);
        }
        doc.put("columns", columns);
        doc.put("headerRows", new ArrayList<>());
        doc.put("rows", new ArrayList<>());
        doc.put("cells", new LinkedHashMap<>());
        doc.put("merges", new ArrayList<>());
        doc.put("freeze", GridDocs.map("rows", 0, "cols", 1));
        return doc;
    }

    static Map<String, Object> infoSheet(String key) {
        Map<String, Object> doc = GridDocs.map("key", key, "dimension", 2, "rowsMode", "FIXED");
        doc.put("columns", new ArrayList<>(List.of(GridDocs.column("i1", "Item", "text"), GridDocs.column("i2", "Value", "text"))));
        doc.put("headerRows", new ArrayList<>());
        doc.put("rows", new ArrayList<>(List.of(GridDocs.map("id", "q1", "label", "A"), GridDocs.map("id", "q2", "label", "B"))));
        Map<String, Object> cells = new LinkedHashMap<>();
        cells.put("q1:i1", GridDocs.map("value", "Criteria 1"));
        cells.put("q2:i1", GridDocs.map("value", "Criteria 2"));
        cells.put("q1:i2", GridDocs.map("value", "Note", "kind", "fixed"));
        cells.put("q2:i2", GridDocs.map("value", "Note", "kind", "fixed"));
        doc.put("cells", cells);
        doc.put("merges", new ArrayList<>());
        return doc;
    }

    private static Pinned pin(byte[] v2) {
        PocV2Doc.Result r = PocV2Doc.check(v2);
        if (!r.accepted()) {
            throw new IllegalStateException("template refused: " + r.problems());
        }
        return new Pinned(r.checked().sheets());
    }

    private static String fill(Object... sheets) {
        return GridDocs.json(GridDocs.map("formatVersion", 3, "sheets", new ArrayList<>(List.of(sheets))));
    }

    private static Map<String, Object> sheetRows(String key, Object... rows) {
        return GridDocs.map("key", key, "rows", new ArrayList<>(List.of(rows)));
    }

    private static Map<String, Object> row(String id, Object... cells) {
        return GridDocs.map("id", id, "cells", GridDocs.map(cells));
    }

    public static void main(String[] args) {
        int bad = 0;
        // ---- the templates
        Pinned plant = pin(plantLikeTemplate());
        int inputCells = plant.sheets().get(0).inputs().size();
        Map<String, Integer> kinds = new TreeMap<>();
        plant.sheets().get(0).inputs().values().forEach(c -> kinds.merge((c.cls().equals("reviewer") ? "reviewer " : "") + (c.type() == null
                ? "text (as its column)" : c.type()), 1, Integer::sum));
        System.out.println("plant-like template: sheets=" + plant.sheets().size() + ", cells with their own type or class=" + inputCells + " " + kinds);
        Pinned data = pin(GridDocs.bytes(GridDocs.map("formatVersion", 2, "sheets", new ArrayList<>(List.of(dataSheet("main"))))));
        Map<String, Object> criteria = infoSheet("criteria");
        criteria.put("fillable", false);
        criteria.put("title", "Criteria");
        Pinned multi = pin(GridDocs.bytes(GridDocs.map("formatVersion", 2, "sheets",
                new ArrayList<>(List.of(criteria, dataSheet("data"), infoSheet("overall"))))));
        Set<String> files = new HashSet<>(List.of("f-1", "f-2", "f-3", "f-4", "f-5", "f-6", "f-7"));

        // ---- generated good fills
        Random random = new Random(20261112);
        int good = 0;
        int goodAccepted = 0;
        int hashStable = 0;
        int completeOk = 0;
        for (int n = 0; n < 300; n++) {
            Map<String, Object> cells = new LinkedHashMap<>();
            List<Object> rows = new ArrayList<>();
            Pinned pinned;
            Object doc;
            int shape = n % 3;
            if (shape == 0) {
                pinned = plant;
                for (int r = 13; r <= 28; r++) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    if (random.nextBoolean()) {
                        c.put("c6", random.nextBoolean());
                    }
                    int k = random.nextInt(4);
                    if (k > 0) {
                        List<String> ids = new ArrayList<>(files).subList(0, k);
                        c.put("c7", ids);
                    }
                    if (!c.isEmpty()) {
                        rows.add(GridDocs.map("id", "r" + r, "cells", c));
                    }
                }
                rows.add(row("r5", "c4", String.valueOf(random.nextInt(1000)), "c3", "x"));
                doc = fill(sheetRows("main", rows.toArray()));
            } else if (shape == 1) {
                pinned = data;
                int count = 1 + random.nextInt(60);
                for (int r = 0; r < count; r++) {
                    rows.add(row("a" + r, "d1", "Item " + r, "d2", String.valueOf(random.nextInt(100000)) + "." + (10 + random.nextInt(89)),
                            "d3", "2026-0" + (1 + random.nextInt(9)) + "-1" + random.nextInt(9), "d4", "Pass", "d6", random.nextBoolean()));
                }
                doc = fill(sheetRows("main", rows.toArray()));
            } else {
                pinned = multi;
                List<Object> dataRows = new ArrayList<>();
                for (int r = 0; r < 10; r++) {
                    dataRows.add(row("a" + r, "d1", "Item " + r, "d2", String.valueOf(r)));
                }
                doc = fill(sheetRows("data", dataRows.toArray()));
            }
            good++;
            byte[] raw = ((String) doc).getBytes(StandardCharsets.UTF_8);
            Result res = check(raw, pinned, files, Role.FILLER);
            if (res.accepted()) {
                goodAccepted++;
                // the same fill-in with every object's keys in the reverse order has the same canonical text and hash
                Result again = check(PocV2Doc.reversedKeys((String) doc).getBytes(StandardCharsets.UTF_8), pinned, files, Role.FILLER);
                if (again.accepted() && again.canonical().equals(res.canonical()) && java.util.Arrays.equals(again.hash(), res.hash())) {
                    hashStable++;
                }
                List<Problem> complete = completeness(res.canonical(), pinned, files);
                // the plant-like form has required numbers and required attachments: complete only when they are all there
                if (shape != 0 && complete.isEmpty() || shape == 0) {
                    completeOk++;
                }
            } else {
                System.out.println("  GOOD FILL REFUSED: " + res.problems());
            }
        }
        System.out.println("good generated fill-ins: " + good + ", accepted " + goodAccepted + ", canonical text and hash independent of key order "
                + hashStable + ", completeness answered as expected " + completeOk);
        if (goodAccepted != good || hashStable != good) {
            bad++;
        }

        // ---- the plant form fully filled: complete
        List<Object> full = new ArrayList<>();
        for (int r = 13; r <= 28; r++) {
            full.add(row("r" + r, "c6", r % 2 == 0, "c7", new ArrayList<>(List.of("f-1"))));
        }
        for (int r = 5; r <= 8; r++) {
            full.add(row("r" + r, "c4", "12"));
        }
        full.add(row("r35", "c7", new ArrayList<>(List.of("f-2"))));
        Result fullRes = check(fill(sheetRows("main", full.toArray())).getBytes(StandardCharsets.UTF_8), plant, files, Role.FILLER);
        List<Problem> noGaps = fullRes.accepted() ? completeness(fullRes.canonical(), plant, files) : List.of();
        System.out.println("plant form filled completely: accepted=" + fullRes.accepted() + ", still missing=" + noGaps.size());
        // the same with a file removed since (f-1 no longer exists): the required attachments are missing again
        List<Problem> removed = completeness(fullRes.canonical(), plant, Set.of("f-2", "f-3"));
        System.out.println("the same after file f-1 was removed: still missing=" + removed.size() + " (4 required attachment cells)");
        if (!fullRes.accepted() || !noGaps.isEmpty() || removed.size() != 4) {
            bad++;
        }

        // ---- hostile fills: each carries the marker text; the answer must have the expected code and never repeat it
        Object[][] hostile = {
            {"a yes/no cell given text", "value_type", plant, Role.FILLER, fill(sheetRows("main", row("r13", "c6", "yes")))},
            {"a yes/no cell given a number", "shape", plant, Role.FILLER, fill(sheetRows("main", GridDocs.map("id", "r13", "cells", GridDocs.map("c6", 1))))},
            {"an attachment cell given text", "value_type", plant, Role.FILLER, fill(sheetRows("main", row("r13", "c7", SECRET)))},
            {"an attachment cell given yes", "value_type", plant, Role.FILLER, fill(sheetRows("main", row("r13", "c7", true)))},
            {"an attachment of a file of another fill-in", "file_not_of_this_fill", plant, Role.FILLER,
                fill(sheetRows("main", row("r13", "c7", new ArrayList<>(List.of("f-1", "g-other")))))},
            {"an attachment naming one file twice", "file_duplicate", plant, Role.FILLER, fill(sheetRows("main", row("r13", "c7", new ArrayList<>(List.of("f-1", "f-1")))))},
            {"an attachment list over the cell's own limit of two", "too_many_files", plant, Role.FILLER,
                fill(sheetRows("main", row("r41", "c7", new ArrayList<>(List.of("f-1", "f-2", "f-3")))))},
            {"an attachment list of six", "too_many_files", plant, Role.FILLER,
                fill(sheetRows("main", row("r13", "c7", new ArrayList<>(List.of("f-1", "f-2", "f-3", "f-4", "f-5", "f-6")))))},
            {"a file identifier that is not an identifier", "file_id_shape", plant, Role.FILLER,
                fill(sheetRows("main", row("r13", "c7", new ArrayList<>(List.of("../" + SECRET)))))},
            {"a list inside a list", "too_deep", plant, Role.FILLER,
                fill(sheetRows("main", GridDocs.map("id", "r13", "cells", GridDocs.map("c7", new ArrayList<>(List.of(new ArrayList<>(List.of("f-1"))))))))},
            {"a text cell given a list of files", "value_type", plant, Role.FILLER, fill(sheetRows("main", row("r5", "c3", new ArrayList<>(List.of("f-1")))))},
            {"a number cell given letters", "value_type", plant, Role.FILLER, fill(sheetRows("main", row("r5", "c4", SECRET)))},
            {"a date cell given an impossible day", "value_type", plant, Role.FILLER, fill(sheetRows("main", row("r3", "c3", "2026-02-31")))},
            {"a picklist value outside the list", "value_type", plant, Role.FILLER, fill(sheetRows("main", row("r4", "c3", SECRET)))},
            {"a cell that is fixed text", "cell_not_input", plant, Role.FILLER, fill(sheetRows("main", row("r2", "c1", SECRET)))},
            {"a column that does not exist", "cell_unknown", plant, Role.FILLER, fill(sheetRows("main", row("r2", "zz" + SECRET.length(), "x")))},
            {"a sheet that is not in the pinned version", "sheet_unknown", plant, Role.FILLER, fill(sheetRows("other", row("a", "c2", "x")))},
            {"the same sheet twice", "duplicate_sheet", multi, Role.FILLER, fill(sheetRows("data", row("a1", "d1", "x")), sheetRows("data", row("a2", "d1", "y")))},
            {"a second sheet the template does not have", "too_many_sheets", plant, Role.FILLER, fill(sheetRows("main", row("r2", "c2", "x")), sheetRows("main", row("r3", "c2", "y")))},
            {"a cell in an information tab", "sheet_not_fillable", multi, Role.FILLER, fill(sheetRows("criteria", row("q1", "i2", "x")))},
            {"a row of another sheet's column", "cell_unknown", multi, Role.FILLER, fill(sheetRows("data", row("a1", "i1", "x")))},
            {"a new row on a sheet with fixed rows", "rows_fixed", plant, Role.FILLER, fill(sheetRows("main", row("newrow", "c2", "x")))},
            {"the same row twice", "duplicate_row", data, Role.FILLER, fill(sheetRows("main", row("a", "d1", "x"), row("a", "d1", "y")))},
            {"a row named like a column", "row_id_reserved", data, Role.FILLER, fill(sheetRows("main", row("d1", "d1", "x")))},
            {"markup in a text", "unsafe_text", data, Role.FILLER, fill(sheetRows("main", row("a", "d1", "<script>" + SECRET)))},
            {"a text of 1,001 characters", "text_too_long", data, Role.FILLER, fill(sheetRows("main", row("a", "d1", "x".repeat(1001))))},
            {"a reviewer's cell typed by the filler", "cell_wrong_class", plant, Role.FILLER, fill(sheetRows("main", row("r13", "c9", "ok")))},
            {"a filler's cell typed by the reviewer", "cell_wrong_class", plant, Role.REVIEWER, fill(sheetRows("main", row("r5", "c4", "5")))},
            {"a repeated key", "not_json", plant, Role.FILLER, "{\"formatVersion\":3,\"formatVersion\":3,\"sheets\":[]}"},
            {"not JSON", "not_json", plant, Role.FILLER, "{\"formatVersion\":3,\"sheets\":[" + SECRET},
            {"a format number that is not 3", "format", plant, Role.FILLER, "{\"formatVersion\":2,\"sheets\":[]}"},
            {"an extra property", "shape", plant, Role.FILLER, "{\"formatVersion\":3,\"sheets\":[],\"" + SECRET + "\":1}"},
            {"nesting too deep", "too_deep", plant, Role.FILLER, "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]"},
            {"a document over 1 MB", "too_large", plant, Role.FILLER, "{\"formatVersion\":3,\"sheets\":[],\"x\":\"" + "a".repeat(1_100_000) + "\"}"},
            {"nine sheets", "too_many_sheets", multi, Role.FILLER, nineSheets()},
        };
        int refused = 0;
        int leaked = 0;
        for (Object[] h : hostile) {
            Result r = check(((String) h[4]).getBytes(StandardCharsets.UTF_8), (Pinned) h[2], files, (Role) h[3]);
            boolean expected = !r.accepted() && r.problems().stream().anyMatch(p -> p.code().equals(h[1]));
            boolean repeated = r.problems().stream().anyMatch(p -> p.place().contains(SECRET) || p.code().contains(SECRET));
            if (expected) {
                refused++;
            } else {
                System.out.println("  NOT AS EXPECTED: " + h[0] + " -> " + (r.accepted() ? "accepted" : r.problems()));
            }
            if (repeated) {
                leaked++;
            }
        }
        System.out.println("hostile fill-ins: " + hostile.length + ", refused with the expected stable code " + refused + ", answers that repeated the typed marker " + leaked);
        if (refused != hostile.length || leaked != 0) {
            bad++;
        }
        System.exit(bad == 0 ? 0 : 1);
    }

    private static String nineSheets() {
        List<Object> sheets = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            sheets.add(sheetRows(i == 0 ? "criteria" : "s" + i, row("a" + i, "d1", "x")));
        }
        return fill(sheets.toArray());
    }

    static Set<String> unusedSet() {
        return new HashSet<>();
    }
}
