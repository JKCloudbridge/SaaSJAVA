package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetDocument.Tab;
import app.platform.sheets.internal.SheetGrid.Cell;
import app.platform.sheets.internal.SheetGrid.Column;
import app.platform.sheets.internal.SheetGrid.Line;
import app.platform.sheets.internal.formula.FormulaLimits;
import app.platform.sheets.internal.formula.FormulaParser;
import app.platform.sheets.internal.formula.FormulaPrinter;
import app.platform.sheets.internal.formula.FormulaRefused;
import app.platform.sheets.internal.formula.FormulaTranslator;
import app.platform.sheets.internal.formula.FormulaTranslator.Layout;
import app.platform.sheets.internal.formula.Node;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns the formulas of an uploaded worksheet into formulas of the sheet where it can, and says plainly what it could
 * not (SH2, ADR-0252). <strong>The file is never refused for a formula</strong>: a formula that the translator cannot
 * place, the gate does not accept, or the checks of the sheet refuse (a cycle, a chain that is too long) stays what it
 * always was, the last value the file holds, and is counted under the reason that fits.
 *
 * <ul>
 *   <li>In a sheet that names its rows, a formula in a body cell becomes the formula of that cell.</li>
 *   <li>In a sheet where the filler adds all the rows, a formula in the <em>first</em> body row that points only at
 *       cells of its own row (or at the whole body of a column) becomes the formula of its column, the calculated
 *       column, drawn at every row the filler adds. The other body rows are not kept, as before.</li>
 * </ul>
 *
 * <p>A shared formula has already been given to each of its cells by the reader; an array formula was not given a text
 * and stays its last value. What a formula says is never logged: only counts leave this class.
 */
final class FormulaImport {

    /**
     * The formulas that were accepted.
     *
     * @param cells the stored form of each formula cell, by cell key
     * @param columns the stored form of each calculated column, by column identifier
     * @param positions the positions in the file, as the builder keys them, of the cells that became formulas
     */
    record Result(Map<String, String> cells, Map<String, String> columns, Set<Long> positions) {

        static Result none() {
            return new Result(Map.of(), Map.of(), Set.of());
        }

        @Override
        public String toString() {
            return "Result[redacted]";
        }
    }

    private FormulaImport() {
    }

    /**
     * Converts what can be converted and counts the rest.
     *
     * @param raw the worksheet
     * @param named whether the sheet names its rows (dimension 2)
     * @param bodyStart the first body row of the file, zero based
     * @param maxRow the last row of the table, zero based
     * @param columns the columns of the grid
     * @param bodyRowIds the identifiers of the named rows, in order (empty when the filler adds the rows)
     * @param covered the positions under a merged area
     * @param limits the bounds of the formulas
     * @return the accepted formulas
     */
    static Result convert(RawSheet raw, boolean named, int bodyStart, int maxRow, List<Column> columns,
            List<String> bodyRowIds, Set<Long> covered, FormulaLimits limits) {
        ImportNote.Counts notes = raw.notes;
        notes.remove(ImportNote.FORMULAS);
        if (raw.formulas.isEmpty()) {
            return Result.none();
        }
        List<String> columnIds = new ArrayList<>();
        columns.forEach(column -> columnIds.add(column.id()));
        // Where the filler adds the rows, the row of the formulas is the first row at or below the labels that holds
        // one: a form may have a band of sub-headings between its labels and its first row of data.
        int formulaRow = bodyStart;
        if (!named) {
            Integer first = raw.formulas.ceilingKey(bodyStart);
            formulaRow = first == null ? bodyStart : first;
        }
        List<String> fileRows = new ArrayList<>();
        for (int i = 0; i < (named ? bodyStart : formulaRow); i++) {
            fileRows.add("");
        }
        if (named) {
            fileRows.addAll(bodyRowIds);
        }
        Layout layout = new Layout(columnIds, fileRows, !named, Math.max(1, maxRow - formulaRow + 1));
        int[] left = new int[3];
        Map<String, String> cells = new LinkedHashMap<>();
        Map<String, String> columnFormulas = new LinkedHashMap<>();
        Map<String, Long> positions = new LinkedHashMap<>();
        for (Map.Entry<Integer, TreeMap<Integer, RawSheet.Formula>> row : raw.formulas.entrySet()) {
            for (Map.Entry<Integer, RawSheet.Formula> found : row.getValue().entrySet()) {
                int r = row.getKey();
                int c = found.getKey();
                if (c >= columnIds.size() || !named && r > formulaRow) {
                    continue;
                }
                long where = ((long) r << 32) | c;
                boolean usable = named ? r >= bodyStart && r <= maxRow && !covered.contains(where) : r == formulaRow;
                if (!usable || found.getValue().text() == null || positions.size() >= limits.maxFormulas()) {
                    left[2]++;
                    continue;
                }
                try {
                    String stored = FormulaTranslator.toIds(withoutPrefixes(found.getValue().text()), r + 1, layout);
                    Node tree = FormulaParser.parse(stored, limits);
                    String canonical = FormulaPrinter.print(tree);
                    String node = named ? "c:" + bodyRowIds.get(r - bodyStart) + ":" + columnIds.get(c)
                            : "k:" + columnIds.get(c);
                    if (named) {
                        cells.put(node.substring(2), canonical);
                    } else {
                        columnFormulas.put(node.substring(2), canonical);
                    }
                    positions.put(node, where);
                } catch (FormulaRefused refused) {
                    count(refused.reason(), left);
                }
            }
        }
        demote(columns, named, bodyRowIds, limits, cells, columnFormulas, positions, left);
        notes.add(ImportNote.FORMULAS_KEPT, positions.size());
        notes.add(ImportNote.FORMULAS_FUNCTION, left[0]);
        notes.add(ImportNote.FORMULAS_REFERENCE, left[1]);
        notes.add(ImportNote.FORMULAS, left[2]);
        return new Result(cells, columnFormulas, new HashSet<>(positions.values()));
    }

    /**
     * Runs the checks of the sheet on what was accepted and gives back, as last values, every formula they refuse
     * (a cycle, a chain that is too long, a text that holds markup), until the rest is clean.
     */
    private static void demote(List<Column> columns, boolean named, List<String> bodyRowIds, FormulaLimits limits,
            Map<String, String> cells, Map<String, String> columnFormulas, Map<String, Long> positions, int[] left) {
        FormulaChecks checks = new FormulaChecks(limits);
        for (int pass = 0; pass <= positions.size() + 1; pass++) {
            if (cells.isEmpty() && columnFormulas.isEmpty()) {
                return;
            }
            List<FormulaChecks.Offence> offences = checks.find(tab(columns, named, bodyRowIds, cells, columnFormulas),
                    "grid");
            if (offences.isEmpty()) {
                return;
            }
            for (FormulaChecks.Offence offence : offences) {
                if (offence.node() == null) {
                    cells.clear();
                    columnFormulas.clear();
                    left[2] += positions.size();
                    positions.clear();
                    return;
                }
                String key = offence.node().substring(2);
                boolean removed = offence.node().startsWith("c:") ? cells.remove(key) != null
                        : columnFormulas.remove(key) != null;
                if (removed) {
                    positions.remove(offence.node());
                    count(offence.reason(), left);
                }
            }
        }
    }

    private static Tab tab(List<Column> columns, boolean named, List<String> bodyRowIds, Map<String, String> cells,
            Map<String, String> columnFormulas) {
        Map<String, Cell> grid = new LinkedHashMap<>();
        cells.keySet().forEach(key -> grid.put(key, new Cell(null, null, SheetGrid.Kind.FIXED)));
        List<Line> rows = new ArrayList<>();
        if (named) {
            bodyRowIds.forEach(id -> rows.add(new Line(id, null, null)));
        }
        SheetGrid body = new SheetGrid(named ? 2 : 1, named ? SheetGrid.RowsMode.FIXED : SheetGrid.RowsMode.REPEATABLE,
                columns, List.of(), rows, grid, List.of());
        return new Tab("main", null, true, null, null, Map.of(), body, Map.of(), Map.of()).withFormulas(cells,
                columnFormulas);
    }

    /** Which of the three counts a reason belongs to: a function, a place, or anything else. */
    private static void count(String reason, int[] left) {
        switch (reason) {
            case "function_not_allowed" -> left[0]++;
            case "row_outside_sheet", "column_outside_sheet", "character_not_allowed", "name_not_allowed",
                    "unknown_column", "unknown_row", "bad_reference_token", "bad_range" -> left[1]++;
            default -> left[2]++;
        }
    }

    /** The prefixes a newer function carries in a file ({@code _xlfn.}, {@code _xlws.}), outside text constants. */
    static String withoutPrefixes(String text) {
        StringBuilder out = new StringBuilder();
        boolean quoted = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            }
            if (!quoted && (text.regionMatches(true, i, "_xlfn.", 0, 6) || text.regionMatches(true, i, "_xlws.", 0,
                    6))) {
                i += 6;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
