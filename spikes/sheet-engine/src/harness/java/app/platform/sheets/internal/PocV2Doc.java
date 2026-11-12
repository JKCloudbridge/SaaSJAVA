package app.platform.sheets.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * POC 2, WP2 and WP3: a prototype of the grid FORMAT VERSION 2 (a list of sheets, a type, a requirement and a class for an input cell),
 * checked in the same staged, message-free style as the real {@link SheetGridValidator}. It does not copy that validator: every
 * sheet's body goes through the REAL validator unchanged (as a version 1 body), so each sheet keeps all the rules of version 1; this
 * class adds only what version 2 adds. The first stage with a problem answers alone; a problem is a stable code and a place made of
 * positions, sheet keys and identifiers, never a typed text.
 *
 * <p>Document: {@code {"formatVersion":2,"sheets":[{"key":"main", ...version 1 body..., "freeze":{"rows":3,"cols":0},
 * "print":{...}, "inputs":{"rowId:columnId":{"type":"attachment","required":true,"class":"filler","maxFiles":5}}}]}}.
 */
public final class PocV2Doc {

    public static final int MAX_SHEETS = 8;
    public static final int MAX_BYTES = 1_000_000;
    public static final int MAX_FIXED_TEXT = 5_000;
    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");
    private static final Set<String> TYPES = Set.of("text", "number", "date", "picklist", "boolean", "attachment");
    private static final Set<String> CLASSES = Set.of("fixed", "filler", "reviewer");
    private static final Set<String> PAPERS = Set.of("A4", "A3", "LETTER");
    private static final Set<String> ORIENTATIONS = Set.of("PORTRAIT", "LANDSCAPE");
    private static final int MOST_PROBLEMS = 20;

    private static final JsonMapper STRICT = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final JsonMapper WRITER = JsonMapper.builder().build();

    /** The real validator with the one limit this prototype changes: a fixed text may be longer (5,000) than version 1 allows. */
    static final SheetGridValidator SHEET = new SheetGridValidator(
            new SheetProperties.Limits(MAX_BYTES, 500, 100, 10, MAX_FIXED_TEXT, 200, 100, 2_000, 12, 1_000), new SheetSchemas());

    public record Problem(String code, String place) {
    }

    /** What the author states about an input cell; absent means "as its column". */
    public record CellSpec(String type, boolean required, String cls, List<String> picklist, int maxFiles) {
    }

    public record Print(String paper, String orientation, boolean fitToWidth, boolean repeatHeaderRows) {
    }

    /** @param title the name of the tab (organization text, at most 31 characters, no markup), or null
     *  @param fillable false for an information tab: no fill-in may hold a cell of it */
    public record Sheet(String key, SheetGrid grid, int freezeRows, int freezeCols, Print print, Map<String, CellSpec> inputs,
            String title, boolean fillable) {
    }

    public record Checked(List<Sheet> sheets, String canonical, byte[] hash, int bytes) {
    }

    public record Result(Checked checked, List<Problem> problems) {
        public boolean accepted() {
            return checked != null;
        }
    }

    private PocV2Doc() {
    }

    // ---------------------------------------------------------------------------------------------------------------

    public static Result check(byte[] raw) {
        if (raw.length > MAX_BYTES) {
            return rejected("too_large", "document");
        }
        if (SheetGridValidator.nestingOf(raw) > 14) {
            return rejected("too_deep", "document");
        }
        JsonNode root;
        try {
            root = STRICT.readTree(raw);
        } catch (RuntimeException e) {
            return rejected("not_json", "document");
        }
        List<Problem> shape = new ArrayList<>();
        if (root == null || !root.isObject()) {
            return rejected("shape", "document");
        }
        for (Map.Entry<String, JsonNode> e : root.properties()) {
            if (!e.getKey().equals("formatVersion") && !e.getKey().equals("sheets")) {
                shape.add(new Problem("shape", "document.…"));
            }
        }
        if (!root.path("formatVersion").isInt() || root.path("formatVersion").asInt() != 2) {
            shape.add(new Problem("format", "formatVersion"));
        }
        JsonNode sheetsNode = root.path("sheets");
        if (!sheetsNode.isArray() || sheetsNode.isEmpty()) {
            shape.add(new Problem("shape", "sheets"));
        } else if (sheetsNode.size() > MAX_SHEETS) {
            shape.add(new Problem("too_many_sheets", "sheets"));
        }
        if (!shape.isEmpty()) {
            return new Result(null, shape);
        }
        // stage: keys
        List<Problem> keys = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < sheetsNode.size(); i++) {
            JsonNode s = sheetsNode.get(i);
            if (!s.isObject()) {
                keys.add(new Problem("shape", "sheets[" + i + "]"));
                continue;
            }
            JsonNode k = s.path("key");
            if (!k.isString() || !KEY.matcher(k.asString()).matches()) {
                keys.add(new Problem("sheet_key", "sheets[" + i + "].key"));
            } else if (!seen.add(k.asString())) {
                keys.add(new Problem("duplicate_sheet_key", "sheets[" + i + "].key"));
            }
            for (Map.Entry<String, JsonNode> e : s.properties()) {
                if (!Set.of("key", "title", "fillable", "freeze", "print", "inputs").contains(e.getKey()) && !V1_PARTS.contains(e.getKey())) {
                    keys.add(new Problem("shape", "sheets[" + i + "].…"));
                }
            }
        }
        if (!keys.isEmpty()) {
            return capped(keys);
        }
        // stage: every sheet through the REAL version 1 validator, unchanged
        List<SheetGridValidator.Checked> bodies = new ArrayList<>();
        List<Problem> body = new ArrayList<>();
        for (int i = 0; i < sheetsNode.size(); i++) {
            tools.jackson.databind.node.ObjectNode copy = (tools.jackson.databind.node.ObjectNode) sheetsNode.get(i).deepCopy();
            for (String extra : List.of("key", "title", "fillable", "freeze", "print", "inputs")) {
                copy.remove(extra);
            }
            copy.put("formatVersion", 1);
            SheetGridValidator.Result r = SHEET.check(WRITER.writeValueAsString(copy).getBytes(StandardCharsets.UTF_8));
            if (r.accepted()) {
                bodies.add(r.checked());
            } else {
                for (SheetGridValidator.Problem p : r.problems()) {
                    body.add(new Problem(p.code(), "sheets[" + i + "]." + p.place()));
                }
            }
        }
        if (!body.isEmpty()) {
            return capped(body);
        }
        // stage: freeze and print per sheet, then the input cells
        List<Problem> extras = new ArrayList<>();
        List<Sheet> sheets = new ArrayList<>();
        for (int i = 0; i < sheetsNode.size(); i++) {
            JsonNode s = sheetsNode.get(i);
            SheetGrid grid = bodies.get(i).grid();
            String at = "sheets[" + i + "]";
            int fr = 0;
            int fc = 0;
            JsonNode freeze = s.path("freeze");
            if (!freeze.isMissingNode()) {
                if (!freeze.isObject() || !freeze.path("rows").isInt() || !freeze.path("cols").isInt()
                        || freeze.size() != 2) {
                    extras.add(new Problem("freeze", at + ".freeze"));
                } else {
                    fr = freeze.path("rows").asInt();
                    fc = freeze.path("cols").asInt();
                    int rowCap = grid.headerRows().size() + grid.rows().size();
                    if (fr < 0 || fc < 0 || fr > Math.min(rowCap, 50) || fc > Math.min(grid.columns().size(), 20)) {
                        extras.add(new Problem("freeze", at + ".freeze"));
                    }
                }
            }
            Print print = null;
            JsonNode pr = s.path("print");
            if (!pr.isMissingNode()) {
                if (!pr.isObject() || !PAPERS.contains(pr.path("paper").asString("")) || !ORIENTATIONS.contains(pr.path("orientation").asString(""))
                        || !pr.path("fitToWidth").isBoolean() || !pr.path("repeatHeaderRows").isBoolean() || pr.size() != 4) {
                    extras.add(new Problem("print", at + ".print"));
                } else {
                    print = new Print(pr.path("paper").asString(), pr.path("orientation").asString(), pr.path("fitToWidth").asBoolean(),
                            pr.path("repeatHeaderRows").asBoolean());
                }
            }
            Map<String, CellSpec> inputs = new TreeMap<>();
            JsonNode in = s.path("inputs");
            if (!in.isMissingNode()) {
                if (!in.isObject()) {
                    extras.add(new Problem("inputs", at + ".inputs"));
                } else {
                    inputProblems(in, grid, at, inputs, extras);
                }
            }
            String title = null;
            if (s.has("title")) {
                title = s.path("title").asString(null);
                if (title == null || title.isEmpty() || title.length() > 31 || CellValues.unsafe(title)) {
                    extras.add(new Problem("title", at + ".title"));
                    title = null;
                }
            }
            boolean fillable = true;
            if (s.has("fillable")) {
                if (!s.path("fillable").isBoolean()) {
                    extras.add(new Problem("fillable", at + ".fillable"));
                } else {
                    fillable = s.path("fillable").asBoolean();
                }
            }
            sheets.add(new Sheet(s.path("key").asString(), grid, fr, fc, print, inputs, title, fillable));
        }
        if (!extras.isEmpty()) {
            return capped(extras);
        }
        String canonical = write(sheets, bodies);
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            return rejected("too_large", "document");
        }
        return new Result(new Checked(sheets, canonical, SheetGridWriter.sha256(bytes), bytes.length), List.of());
    }

    private static final Set<String> V1_PARTS = Set.of("dimension", "rowsMode", "columns", "headerRows", "rows", "cells", "merges");

    private static void inputProblems(JsonNode in, SheetGrid grid, String at, Map<String, CellSpec> out, List<Problem> problems) {
        FillStructure structure = new FillStructure(grid);
        if (grid.dimension() != 2) {
            problems.add(new Problem("inputs_need_named_rows", at + ".inputs"));
            return;
        }
        for (Map.Entry<String, JsonNode> e : in.properties()) {
            String key = e.getKey();
            String place = at + ".inputs.…";
            int colon = key.indexOf(':');
            if (colon <= 0 || structure.column(key.substring(colon + 1)) == null || !structure.isNamedRow(key.substring(0, colon))) {
                problems.add(new Problem("input_unknown_cell", place));
                continue;
            }
            if (!structure.isInput(key.substring(0, colon), key.substring(colon + 1))) {
                problems.add(new Problem("input_not_an_input_cell", place));
                continue;
            }
            JsonNode v = e.getValue();
            if (!v.isObject()) {
                problems.add(new Problem("inputs", place));
                continue;
            }
            for (Map.Entry<String, JsonNode> p : v.properties()) {
                if (!Set.of("type", "required", "class", "picklist", "maxFiles").contains(p.getKey())) {
                    problems.add(new Problem("inputs", place));
                }
            }
            String type = v.path("type").asString(null);
            if (v.has("type") && (type == null || !TYPES.contains(type))) {
                problems.add(new Problem("input_type", place));
                continue;
            }
            String cls = v.has("class") ? v.path("class").asString("") : "filler";
            if (!CLASSES.contains(cls) || cls.equals("fixed")) {
                problems.add(new Problem("input_class", place));
                continue;
            }
            if (v.has("required") && !v.path("required").isBoolean()) {
                problems.add(new Problem("inputs", place));
                continue;
            }
            List<String> picks = null;
            if ("picklist".equals(type)) {
                JsonNode pl = v.path("picklist");
                if (!pl.isArray() || pl.isEmpty() || pl.size() > 100) {
                    problems.add(new Problem("input_picklist", place));
                    continue;
                }
                picks = new ArrayList<>();
                Set<String> unique = new HashSet<>();
                boolean bad = false;
                for (JsonNode item : pl) {
                    if (!item.isString() || item.asString().isEmpty() || item.asString().length() > 100 || CellValues.unsafe(item.asString())
                            || !unique.add(item.asString())) {
                        bad = true;
                    } else {
                        picks.add(item.asString());
                    }
                }
                if (bad) {
                    problems.add(new Problem("input_picklist", place));
                    continue;
                }
            } else if (v.has("picklist")) {
                problems.add(new Problem("input_picklist", place));
                continue;
            }
            int maxFiles = 0;
            if ("attachment".equals(type)) {
                maxFiles = v.has("maxFiles") ? v.path("maxFiles").asInt(-1) : 5;
                if (!v.path("maxFiles").isMissingNode() && !v.path("maxFiles").isInt() || maxFiles < 1 || maxFiles > 5) {
                    problems.add(new Problem("input_max_files", place));
                    continue;
                }
            } else if (v.has("maxFiles")) {
                problems.add(new Problem("input_max_files", place));
                continue;
            }
            out.put(key, new CellSpec(type, v.path("required").asBoolean(false), cls, picks, maxFiles));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static String write(List<Sheet> sheets, List<SheetGridValidator.Checked> bodies) {
        Map<String, Object> root = new TreeMap<>();
        root.put("formatVersion", 2);
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < sheets.size(); i++) {
            Sheet s = sheets.get(i);
            @SuppressWarnings("unchecked")
            Map<String, Object> one = (Map<String, Object>) sorted(WRITER.readTree(bodies.get(i).canonical()));
            one.remove("formatVersion");
            one.put("key", s.key());
            if (s.title() != null) {
                one.put("title", s.title());
            }
            if (!s.fillable()) {
                one.put("fillable", false);
            }
            if (s.freezeRows() > 0 || s.freezeCols() > 0) {
                Map<String, Object> f = new TreeMap<>();
                f.put("rows", s.freezeRows());
                f.put("cols", s.freezeCols());
                one.put("freeze", f);
            }
            if (s.print() != null) {
                Map<String, Object> p = new TreeMap<>();
                p.put("paper", s.print().paper());
                p.put("orientation", s.print().orientation());
                p.put("fitToWidth", s.print().fitToWidth());
                p.put("repeatHeaderRows", s.print().repeatHeaderRows());
                one.put("print", p);
            }
            if (!s.inputs().isEmpty()) {
                Map<String, Object> ins = new TreeMap<>();
                s.inputs().forEach((k, c) -> {
                    Map<String, Object> m = new TreeMap<>();
                    if (c.type() != null) {
                        m.put("type", c.type());
                    }
                    if (c.required()) {
                        m.put("required", true);
                    }
                    if (!"filler".equals(c.cls())) {
                        m.put("class", c.cls());
                    }
                    if (c.picklist() != null) {
                        m.put("picklist", c.picklist());
                    }
                    if ("attachment".equals(c.type()) && c.maxFiles() != 5) {
                        m.put("maxFiles", c.maxFiles());
                    }
                    ins.put(k, m);
                });
                one.put("inputs", ins);
            }
            list.add(one);
        }
        root.put("sheets", list);
        return WRITER.writeValueAsString(root);
    }

    /** Objects with their keys in order, lists as they are. */
    static Object sorted(JsonNode n) {
        if (n.isObject()) {
            Map<String, Object> m = new TreeMap<>();
            for (Map.Entry<String, JsonNode> e : n.properties()) {
                m.put(e.getKey(), sorted(e.getValue()));
            }
            return m;
        }
        if (n.isArray()) {
            List<Object> l = new ArrayList<>();
            for (JsonNode c : n) {
                l.add(sorted(c));
            }
            return l;
        }
        if (n.isString()) {
            return n.asString();
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isIntegralNumber()) {
            return n.asLong();
        }
        if (n.isNull()) {
            return null;
        }
        return n.asDouble();
    }

    /** The same document with every object's keys in the reverse order, for the test that the hash does not depend on key order. */
    public static String reversedKeys(String json) {
        return WRITER.writeValueAsString(reverse(WRITER.readTree(json)));
    }

    private static Object reverse(JsonNode n) {
        if (n.isObject()) {
            List<Map.Entry<String, JsonNode>> es = new ArrayList<>();
            n.properties().forEach(es::add);
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = es.size() - 1; i >= 0; i--) {
                m.put(es.get(i).getKey(), reverse(es.get(i).getValue()));
            }
            return m;
        }
        if (n.isArray()) {
            List<Object> l = new ArrayList<>();
            for (JsonNode c : n) {
                l.add(reverse(c));
            }
            return l;
        }
        return sorted(n);
    }

    private static Result rejected(String code, String place) {
        return new Result(null, List.of(new Problem(code, place)));
    }

    private static Result capped(List<Problem> problems) {
        return new Result(null, problems.size() > MOST_PROBLEMS ? problems.subList(0, MOST_PROBLEMS) : problems);
    }
}
