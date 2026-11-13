package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetDocument.Tab;
import app.platform.sheets.internal.SheetGrid.Cell;
import app.platform.sheets.internal.SheetGridValidator.Problem;
import app.platform.sheets.internal.formula.FormulaLimits;
import app.platform.sheets.internal.formula.FormulaParser;
import app.platform.sheets.internal.formula.FormulaRefused;
import app.platform.sheets.internal.formula.FormulaWords;
import app.platform.sheets.internal.formula.Node;
import app.platform.sheets.internal.formula.Node.Binary;
import app.platform.sheets.internal.formula.Node.Call;
import app.platform.sheets.internal.formula.Node.Group;
import app.platform.sheets.internal.formula.Node.Negate;
import app.platform.sheets.internal.formula.Node.Percent;
import app.platform.sheets.internal.formula.Node.Range;
import app.platform.sheets.internal.formula.Node.Ref;
import app.platform.sheets.internal.formula.Node.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The stage of the validator that checks the formulas of a sheet (SH2, ADR-0252), in this order: how many there are;
 * where each may be (a calculated column only in a sheet where the filler adds all the rows, a formula cell only on a
 * fixed cell); the gate on each formula (length and nesting first, then the allowlist of parts and functions, see
 * {@link FormulaParser}); that every reference names a column and a row that exist, and that the whole body of a
 * column is named only where the filler adds all the rows; then that no formula depends on itself, directly or through
 * other formulas, and that no chain of formulas is longer than the bound.
 *
 * <p>A problem has the code {@code formula_} followed by the stable reason and fixed words
 * ({@link FormulaWords}); the place is made of schema names, positions and identifiers, and nothing a person typed is
 * repeated. Each formula gives at most one problem. Nothing is calculated here.
 */
final class FormulaChecks {

    private static final String BODY = "";

    private final FormulaLimits limits;

    FormulaChecks(FormulaLimits limits) {
        this.limits = limits;
    }

    /** One formula of the sheet: where it is, and what it parsed to. */
    private record One(String node, String place, String ownRow, Node tree) {
    }

    /**
     * One formula that is not accepted.
     *
     * @param node the formula: {@code c:} and the cell key, or {@code k:} and the column identifier; null for a
     *        problem of the sheet as a whole
     * @param place where, in the words of the validator
     * @param reason the stable reason
     */
    record Offence(String node, String place, String reason) {
    }

    /** The problems of the formulas of a sheet, empty when they are all right. */
    List<Problem> check(Tab tab, String sheetPlace) {
        List<Problem> out = new ArrayList<>();
        for (Offence offence : find(tab, sheetPlace)) {
            out.add(problem(offence.reason(), offence.place()));
        }
        return out;
    }

    /** The formulas of a sheet that are not accepted, each with its reason; empty when they are all right. */
    List<Offence> find(Tab tab, String sheetPlace) {
        List<Offence> out = new ArrayList<>();
        if (tab.formulas().isEmpty() && tab.columnFormulas().isEmpty()) {
            return out;
        }
        SheetGrid body = tab.body();
        if (tab.formulas().size() + tab.columnFormulas().size() > limits.maxFormulas()) {
            out.add(new Offence(null, sheetPlace + ".cells", "too_many_formulas"));
            return out;
        }
        Map<String, Integer> columns = new HashMap<>();
        for (int c = 0; c < body.columns().size(); c++) {
            columns.put(body.columns().get(c).id(), c);
        }
        Map<String, Integer> rows = new HashMap<>();
        int position = 0;
        for (SheetGrid.Line line : body.headerRows()) {
            rows.put(line.id(), position++);
        }
        for (SheetGrid.Line line : body.rows()) {
            rows.put(line.id(), position++);
        }
        List<One> ones = new ArrayList<>();
        for (Map.Entry<String, String> entry : new TreeMap<>(tab.formulas()).entrySet()) {
            String place = sheetPlace + ".cells." + entry.getKey() + ".formula";
            Cell cell = body.cells().get(entry.getKey());
            if (cell == null || cell.value() != null || cell.kind() == SheetGrid.Kind.INPUT
                    || tab.inputs().containsKey(entry.getKey())) {
                out.add(new Offence("c:" + entry.getKey(), place, "not_a_fixed_cell"));
                continue;
            }
            String ownRow = entry.getKey().substring(0, entry.getKey().indexOf(':'));
            one(entry.getValue(), "c:" + entry.getKey(), place, ownRow, body, columns, rows, out, ones);
        }
        for (int c = 0; c < body.columns().size(); c++) {
            String id = body.columns().get(c).id();
            String formula = tab.columnFormulas().get(id);
            if (formula == null) {
                continue;
            }
            String place = sheetPlace + ".columns[" + c + "].formula";
            if (body.dimension() != 1) {
                out.add(new Offence("k:" + id, place, "column_not_allowed"));
                continue;
            }
            one(formula, "k:" + id, place, BODY, body, columns, rows, out, ones);
        }
        if (!out.isEmpty()) {
            return out;
        }
        cycles(ones, tab, body, columns, rows, out);
        return out;
    }

    private void one(String stored, String node, String place, String ownRow, SheetGrid body,
            Map<String, Integer> columns, Map<String, Integer> rows, List<Offence> out, List<One> ones) {
        try {
            Node tree = FormulaParser.parse(stored, limits);
            references(tree, ownRow.equals(BODY), body.dimension() == 1, columns, rows);
            ones.add(new One(node, place, ownRow, tree));
        } catch (FormulaRefused refused) {
            out.add(new Offence(node, place, refused.reason()));
        }
    }

    private static Problem problem(String reason, String place) {
        return new Problem("formula_" + reason, place, FormulaWords.of(reason));
    }

    // ---- the parts of one formula ----

    private void references(Node node, boolean inBody, boolean growingSheet, Map<String, Integer> columns,
            Map<String, Integer> rows) {
        switch (node) {
            case Ref r -> reference(r, growingSheet, columns, rows);
            case Range r -> {
                reference(r.from(), growingSheet, columns, rows);
                reference(r.to(), growingSheet, columns, rows);
                boolean open = r.from().growing() || r.to().growing();
                boolean ownEnd = r.from().row() == null && !r.from().growing()
                        || r.to().row() == null && !r.to().growing();
                boolean explicitEnd = r.from().row() != null || r.to().row() != null;
                if (inBody && (open && ownEnd || ownEnd && explicitEnd)) {
                    throw new FormulaRefused("bad_range");
                }
            }
            case Text t -> {
                if (CellValues.unsafe(t.value())) {
                    throw new FormulaRefused("unsafe_text");
                }
            }
            case Negate n -> references(n.operand(), inBody, growingSheet, columns, rows);
            case Percent p -> references(p.operand(), inBody, growingSheet, columns, rows);
            case Group g -> references(g.inner(), inBody, growingSheet, columns, rows);
            case Binary b -> {
                references(b.left(), inBody, growingSheet, columns, rows);
                references(b.right(), inBody, growingSheet, columns, rows);
            }
            case Call c -> {
                for (Node argument : c.arguments()) {
                    references(argument, inBody, growingSheet, columns, rows);
                }
            }
            default -> {
            }
        }
    }

    private static void reference(Ref r, boolean growingSheet, Map<String, Integer> columns,
            Map<String, Integer> rows) {
        if (!columns.containsKey(r.column())) {
            throw new FormulaRefused("unknown_column");
        }
        if (r.growing()) {
            if (!growingSheet) {
                throw new FormulaRefused("growing_not_allowed");
            }
        } else if (r.row() != null && !rows.containsKey(r.row())) {
            throw new FormulaRefused("unknown_row");
        }
    }

    // ---- who depends on whom ----

    private void cycles(List<One> ones, Tab tab, SheetGrid body, Map<String, Integer> columns,
            Map<String, Integer> rows, List<Offence> out) {
        List<String> order = new ArrayList<>();
        body.headerRows().forEach(line -> order.add(line.id()));
        body.rows().forEach(line -> order.add(line.id()));
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        Map<String, One> byNode = new LinkedHashMap<>();
        for (One one : ones) {
            byNode.put(one.node(), one);
        }
        for (One one : ones) {
            Set<String> to = new LinkedHashSet<>();
            dependencies(one.tree(), one, tab, body, columns, rows, order, to);
            edges.put(one.node(), to);
        }
        Map<String, Integer> state = new HashMap<>();
        Map<String, Integer> depth = new HashMap<>();
        Set<String> reported = new HashSet<>();
        for (String start : edges.keySet()) {
            if (!state.containsKey(start)) {
                walk(start, edges, state, depth, byNode, out, reported);
            }
        }
    }

    /** Depth-first search; the chain is as deep as the longest path to a formula that depends on nothing. */
    private int walk(String node, Map<String, Set<String>> edges, Map<String, Integer> state,
            Map<String, Integer> depth, Map<String, One> byNode, List<Offence> out, Set<String> reported) {
        state.put(node, 1);
        int deepest = 1;
        for (String next : edges.getOrDefault(node, Set.of())) {
            Integer seen = state.get(next);
            if (seen != null && seen == 1) {
                if (reported.add(node)) {
                    out.add(new Offence(node, byNode.get(node).place(), "circular"));
                }
                continue;
            }
            int below = seen == null ? walk(next, edges, state, depth, byNode, out, reported) : depth.get(next);
            deepest = Math.max(deepest, below + 1);
        }
        state.put(node, 2);
        depth.put(node, deepest);
        if (deepest > limits.maxChainDepth() && reported.add(node)) {
            out.add(new Offence(node, byNode.get(node).place(), "chain_too_deep"));
        }
        return deepest;
    }

    private void dependencies(Node node, One one, Tab tab, SheetGrid body, Map<String, Integer> columns,
            Map<String, Integer> rows, List<String> order, Set<String> to) {
        switch (node) {
            case Ref r -> {
                if (r.growing()) {
                    column(r.column(), tab, to);
                } else if (one.ownRow().equals(BODY) && r.row() == null) {
                    column(r.column(), tab, to);
                } else {
                    cell(r.row() == null ? one.ownRow() : r.row(), r.column(), tab, to);
                }
            }
            case Range r -> range(r, one, tab, body, columns, rows, order, to);
            case Negate n -> dependencies(n.operand(), one, tab, body, columns, rows, order, to);
            case Percent p -> dependencies(p.operand(), one, tab, body, columns, rows, order, to);
            case Group g -> dependencies(g.inner(), one, tab, body, columns, rows, order, to);
            case Binary b -> {
                dependencies(b.left(), one, tab, body, columns, rows, order, to);
                dependencies(b.right(), one, tab, body, columns, rows, order, to);
            }
            case Call c -> {
                for (Node argument : c.arguments()) {
                    dependencies(argument, one, tab, body, columns, rows, order, to);
                }
            }
            default -> {
            }
        }
    }

    private static void column(String id, Tab tab, Set<String> to) {
        if (tab.columnFormulas().containsKey(id)) {
            to.add("k:" + id);
        }
    }

    private static void cell(String row, String column, Tab tab, Set<String> to) {
        String key = row + ":" + column;
        if (tab.formulas().containsKey(key)) {
            to.add("c:" + key);
        }
    }

    private void range(Range r, One one, Tab tab, SheetGrid body, Map<String, Integer> columns,
            Map<String, Integer> rows, List<String> order, Set<String> to) {
        int c0 = Math.min(columns.get(r.from().column()), columns.get(r.to().column()));
        int c1 = Math.max(columns.get(r.from().column()), columns.get(r.to().column()));
        boolean fromOpen = r.from().growing();
        boolean toOpen = r.to().growing();
        boolean ownRange = one.ownRow().equals(BODY) && r.from().row() == null && r.to().row() == null;
        if (fromOpen || toOpen || ownRange) {
            for (int c = c0; c <= c1; c++) {
                column(body.columns().get(c).id(), tab, to);
            }
        }
        if (ownRange || fromOpen && toOpen) {
            return;
        }
        // An open end runs from the row that has an identifier to the last one; the body rows are the column's.
        int first = fromOpen ? rowPosition(r.to(), one, rows) : rowPosition(r.from(), one, rows);
        int last = fromOpen || toOpen ? order.size() - 1 : rowPosition(r.to(), one, rows);
        for (int row = Math.min(first, last); row <= Math.max(first, last); row++) {
            for (int c = c0; c <= c1; c++) {
                cell(order.get(row), body.columns().get(c).id(), tab, to);
            }
        }
    }

    private static int rowPosition(Ref end, One one, Map<String, Integer> rows) {
        String id = end.row() == null ? one.ownRow() : end.row();
        Integer position = rows.get(id);
        return position == null ? 0 : position;
    }
}
