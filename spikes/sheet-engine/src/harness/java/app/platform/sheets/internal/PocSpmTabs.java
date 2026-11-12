package app.platform.sheets.internal;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * POC 2, WP3.2. The four VISIBLE worksheets of the supplier workbook loaded as ONE template of format version 2 (the three hidden ones
 * are skipped and counted). The workbook is read from the neutral JSON the throwaway exporter wrote OUTSIDE the repository (it holds
 * the owner's text); this class prints counts only. Each tab becomes a sheet with the version 1 body (named rows, fixed text, merges,
 * widths, heights), a freeze, a print set-up and a fillable flag (the data tab is fillable, the others are information tabs).
 * Formulas come in as their last value here (their stored form is the subject of WP5).
 */
public final class PocSpmTabs {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PocSpmTabs() {
    }

    static String text(JsonNode cell) {
        JsonNode v = cell.path("v");
        if (cell.path("t").asString("").equals("f")) {
            String vt = cell.path("vt").asString("");
            if (vt.equals("n") && v.isNumber()) {
                return new BigDecimal(v.asString()).stripTrailingZeros().toPlainString();
            }
            return vt.equals("s") ? v.asString("") : "";
        }
        return switch (cell.path("t").asString("")) {
            case "s" -> v.asString("");
            case "n" -> new BigDecimal(v.asString()).stripTrailingZeros().toPlainString();
            case "b" -> v.asBoolean() ? "TRUE" : "FALSE";
            default -> "";
        };
    }

    static Map<String, Object> toSheet(JsonNode s, String key, boolean fillable) {
        int rows = Math.max(1, s.path("rows").asInt());
        int cols = Math.max(1, s.path("cols").asInt());
        Map<String, Object> doc = GridDocs.map("key", key, "title", "Tab " + key.substring(1), "dimension", 2, "rowsMode", "FIXED");
        if (!fillable) {
            doc.put("fillable", false);
        }
        List<Map<String, Object>> columns = new ArrayList<>();
        for (int c = 0; c < cols; c++) {
            Map<String, Object> col = GridDocs.column("c" + (c + 1), "Column " + (c + 1), "text");
            int width = s.path("colWidths").get(c) == null ? 0 : s.path("colWidths").get(c).asInt();
            if (width >= 40 && width <= 1000) {
                col.put("width", width);
            }
            columns.add(col);
        }
        doc.put("columns", columns);
        doc.put("headerRows", new ArrayList<>());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            Map<String, Object> line = GridDocs.map("id", "r" + (r + 1));
            JsonNode h = s.path("rowHeights").get(String.valueOf(r));
            if (h != null && h.asInt() >= 16 && h.asInt() <= 400) {
                line.put("height", h.asInt());
            }
            lines.add(line);
        }
        doc.put("rows", lines);
        // merges first: nothing may sit under a merged area but its top left cell
        List<Map<String, Object>> merges = new ArrayList<>();
        Set<String> covered = new HashSet<>();
        for (JsonNode m : s.path("merges")) {
            int r0 = m.path("r0").asInt();
            int c0 = m.path("c0").asInt();
            int r1 = m.path("r1").asInt();
            int c1 = m.path("c1").asInt();
            if (r1 >= rows || c1 >= cols) {
                continue;
            }
            merges.add(GridDocs.map("from", "r" + (r0 + 1) + ":c" + (c0 + 1), "to", "r" + (r1 + 1) + ":c" + (c1 + 1)));
            for (int r = r0; r <= r1; r++) {
                for (int c = c0; c <= c1; c++) {
                    if (r != r0 || c != c0) {
                        covered.add(r + ":" + c);
                    }
                }
            }
        }
        Map<String, Object> cells = new LinkedHashMap<>();
        for (JsonNode cell : s.path("cells")) {
            String t = text(cell);
            if (t.isEmpty() || covered.contains(cell.path("r").asInt() + ":" + cell.path("c").asInt())) {
                continue;
            }
            cells.put("r" + (cell.path("r").asInt() + 1) + ":c" + (cell.path("c").asInt() + 1), GridDocs.map("value", t));
        }
        doc.put("cells", cells);
        doc.put("merges", merges);
        int fr = s.path("freeze").path("rows").asInt();
        int fc = s.path("freeze").path("cols").asInt();
        if (fr > 0 || fc > 0) {
            doc.put("freeze", GridDocs.map("rows", fr, "cols", fc));
        }
        doc.put("print", GridDocs.map("paper", "A4", "orientation", "LANDSCAPE", "fitToWidth", true, "repeatHeaderRows", false));
        return doc;
    }

    public static void main(String[] args) throws Exception {
        JsonNode fixture = JSON.readTree(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8));
        List<Object> sheets = new ArrayList<>();
        int hidden = 0;
        int n = 0;
        StringBuilder perTab = new StringBuilder();
        for (JsonNode s : fixture.path("sheets")) {
            if (s.path("hidden").asBoolean()) {
                hidden++;
                continue;
            }
            n++;
            boolean data = s.path("freeze").path("rows").asInt() > 0; // the one tab with a frozen header band is the data tab
            Map<String, Object> sheet = toSheet(s, "t" + n, data);
            sheets.add(sheet);
            perTab.append(" [t").append(n).append(": ").append(s.path("rows").asInt()).append(" rows x ").append(s.path("cols").asInt())
                    .append(" columns, ").append(((Map<?, ?>) sheet.get("cells")).size()).append(" cells with text, ")
                    .append(((List<?>) sheet.get("merges")).size()).append(" merges, ").append(data ? "fillable" : "information")
                    .append("]");
        }
        String doc = GridDocs.json(GridDocs.map("formatVersion", 2, "sheets", sheets));
        PocV2Doc.Result r = PocV2Doc.check(doc.getBytes(StandardCharsets.UTF_8));
        System.out.println("visible tabs loaded=" + sheets.size() + ", hidden tabs skipped and counted=" + hidden + perTab);
        System.out.println("one version-2 document: accepted=" + r.accepted() + (r.accepted() ? ", " + r.checked().bytes() + " bytes canonical"
                : ", problems=" + r.problems().stream().map(p -> p.code() + "@" + p.place()).limit(10).toList()));
        if (!r.accepted()) {
            System.exit(1);
        }
        PocV2Doc.Result back = PocV2Doc.check(PocV2Doc.reversedKeys(doc).getBytes(StandardCharsets.UTF_8));
        boolean stable = back.accepted() && back.checked().canonical().equals(r.checked().canonical())
                && java.util.Arrays.equals(back.checked().hash(), r.checked().hash());
        System.out.println("same canonical text and hash when the keys are written in reverse order=" + stable);
        // write the canonical text again through the validator: a fixed point
        PocV2Doc.Result again = PocV2Doc.check(r.checked().canonical().getBytes(StandardCharsets.UTF_8));
        System.out.println("canonical text is a fixed point=" + (again.accepted() && again.checked().canonical().equals(r.checked().canonical())));

        // one fill-in covers all the tabs: fill the data tab, refuse a cell on an information tab
        PocFill3.Pinned pinned = new PocFill3.Pinned(r.checked().sheets());
        PocV2Doc.Sheet dataTab = r.checked().sheets().stream().filter(PocV2Doc.Sheet::fillable).findFirst().orElseThrow();
        FillStructure st = new FillStructure(dataTab.grid());
        List<Object> rows = new ArrayList<>();
        int input = 0;
        int filled = 0;
        for (String rowId : st.namedRows()) {
            Map<String, Object> cells = new LinkedHashMap<>();
            for (SheetGrid.Column c : st.columns()) {
                if (st.isInput(rowId, c.id())) {
                    input++;
                    if (filled < 60 && input % 5 == 0) {
                        cells.put(c.id(), "v" + filled);
                        filled++;
                    }
                }
            }
            if (!cells.isEmpty()) {
                rows.add(GridDocs.map("id", rowId, "cells", cells));
            }
        }
        String fill = GridDocs.json(GridDocs.map("formatVersion", 3, "sheets", new ArrayList<>(List.of(GridDocs.map("key", dataTab.key(), "rows", rows)))));
        PocFill3.Result fr = PocFill3.check(fill.getBytes(StandardCharsets.UTF_8), pinned, Set.of(), PocFill3.Role.FILLER);
        System.out.println("data tab: " + input + " input cells (cells with no text), fill-in of " + filled + " cells accepted=" + fr.accepted()
                + (fr.accepted() ? ", canonical " + fr.canonical().length() + " bytes" : " " + fr.problems()));
        PocV2Doc.Sheet info = r.checked().sheets().stream().filter(s -> !s.fillable()).findFirst().orElseThrow();
        String infoFill = GridDocs.json(GridDocs.map("formatVersion", 3, "sheets", new ArrayList<>(List.of(GridDocs.map("key", info.key(),
                "rows", new ArrayList<>(List.of(GridDocs.map("id", "r2", "cells", GridDocs.map("c2", "x")))))))));
        PocFill3.Result ir = PocFill3.check(infoFill.getBytes(StandardCharsets.UTF_8), pinned, Set.of(), PocFill3.Role.FILLER);
        System.out.println("a cell on an information tab refused=" + (!ir.accepted() && ir.problems().get(0).code().equals("sheet_not_fillable")));
        System.exit(stable && fr.accepted() && !ir.accepted() ? 0 : 1);
    }
}
