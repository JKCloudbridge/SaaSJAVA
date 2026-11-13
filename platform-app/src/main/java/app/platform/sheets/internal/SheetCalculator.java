package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetDocument.Tab;
import app.platform.sheets.internal.formula.FormulaEvaluator;
import app.platform.sheets.internal.formula.FormulaLimits;
import app.platform.sheets.internal.formula.FormulaParser;
import app.platform.sheets.internal.formula.FormulaRefused;
import app.platform.sheets.internal.formula.FormulaValues;
import app.platform.sheets.internal.formula.Node;
import app.platform.sheets.internal.formula.XlError;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Calculates every formula of a sheet from the values of its input cells (SH2, ADR-0252): a <strong>pure</strong>
 * function of the pinned sheet and the values, with no database and no clock. This is the one call SH5 uses to give a
 * fill-in its answers, and the call behind the endpoint the author's grid shows the server's answers from. Results
 * are <em>computed, not stored</em>: nothing reads them from a list, and a fill-in derives them from the version it is
 * pinned to.
 *
 * <p>A formula is calculated in the order its references need (a chain of formulas is bounded and checked at
 * save), with
 * the bounds of {@link FormulaLimits}. The answer of each is its kind and value; an error is its code, never hidden
 * (a division by zero is {@code #DIV/0!}, a bound that was reached is {@code #LIMIT!}). Nothing is ever run as code.
 *
 * <p>The values: a number column's cell is a {@link Double}, a yes/no cell a {@link Boolean}, any other cell text; an
 * empty cell is absent. A fixed cell of the sheet is its text. A cell that holds a formula is never given a value by
 * the caller.
 */
final class SheetCalculator {

    private SheetCalculator() {
    }

    /** The kind of an answer. */
    enum Kind {
        NUMBER, TEXT, BOOLEAN, ERROR;

        String json() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * The answer of one formula.
     *
     * @param kind the kind
     * @param value the value as text: the digits of a number (fifteen significant digits), the text itself, TRUE or
     *        FALSE, or the code of an error
     */
    record Answer(Kind kind, String value) {

        @Override
        public String toString() {
            return "Answer[redacted]";
        }
    }

    /**
     * The values of the input cells.
     *
     * @param cells the values of the cells of a sheet that names its rows, by cell key
     * @param rows the values of the rows a filler added to a sheet where the filler adds all the rows, by column
     *        identifier, one map for each row in order
     */
    record Inputs(Map<String, Object> cells, List<Map<String, Object>> rows) {

        /** Copies the values. */
        Inputs {
            cells = Map.copyOf(cells);
            rows = List.copyOf(rows);
        }

        /** No values at all. */
        static Inputs none() {
            return new Inputs(Map.of(), List.of());
        }

        /** The given number of empty body rows. */
        static Inputs emptyRows(int count) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                rows.add(Map.of());
            }
            return new Inputs(Map.of(), rows);
        }
    }

    /**
     * Every answer of a sheet.
     *
     * @param cells the answer of each formula cell, by cell key
     * @param columns the answers of each calculated column, by column identifier, one for each body row
     */
    record Outcome(Map<String, Answer> cells, Map<String, List<Answer>> columns) {

        @Override
        public String toString() {
            return "Outcome[redacted]";
        }
    }

    /**
     * Calculates the sheet.
     *
     * @param tab the sheet
     * @param inputs the values of the input cells
     * @param limits the bounds
     * @return the answers
     */
    static Outcome calculate(Tab tab, Inputs inputs, FormulaLimits limits) {
        Run run = new Run(tab, inputs, limits);
        Map<String, Answer> cells = new TreeMap<>();
        for (String key : new TreeMap<>(tab.formulas()).keySet()) {
            cells.put(key, run.answer(run.cellFormula(key)));
        }
        Map<String, List<Answer>> columns = new LinkedHashMap<>();
        for (SheetGrid.Column column : tab.body().columns()) {
            if (tab.columnFormulas().containsKey(column.id())) {
                List<Answer> answers = new ArrayList<>();
                for (int row = 0; row < run.bodyCount(); row++) {
                    answers.add(run.answer(run.columnFormula(column.id(), row)));
                }
                columns.put(column.id(), answers);
            }
        }
        return new Outcome(cells, columns);
    }

    /** One calculation. */
    private static final class Run implements FormulaEvaluator.Grid {

        private static final int MOST_NESTING = 64;

        private final Tab tab;
        private final Inputs inputs;
        private final FormulaLimits limits;
        private final long deadline;
        private final Map<String, Integer> columnAt = new HashMap<>();
        private final Map<String, Integer> rowAt = new HashMap<>();
        private final List<String> order = new ArrayList<>();
        private final List<String> body = new ArrayList<>();
        private final Map<String, Node> trees = new HashMap<>();
        private final Map<String, Object> memo = new HashMap<>();
        private final Set<String> running = new HashSet<>();
        private int nesting;

        Run(Tab tab, Inputs inputs, FormulaLimits limits) {
            this.tab = tab;
            this.inputs = inputs;
            this.limits = limits;
            this.deadline = System.nanoTime() + limits.maxCalculationMillis() * 1_000_000L;
            SheetGrid grid = tab.body();
            for (int c = 0; c < grid.columns().size(); c++) {
                columnAt.put(grid.columns().get(c).id(), c);
            }
            grid.headerRows().forEach(line -> order.add(line.id()));
            grid.rows().forEach(line -> order.add(line.id()));
            if (grid.dimension() == 1) {
                for (int i = 0; i < inputs.rows().size(); i++) {
                    order.add("#" + i);
                    body.add("#" + i);
                }
            }
            for (int i = 0; i < order.size(); i++) {
                rowAt.put(order.get(i), i);
            }
        }

        int bodyCount() {
            return body.size();
        }

        // ---- which formula, and its answer ----

        Object cellFormula(String key) {
            return value("c:" + key, key.substring(0, key.indexOf(':')), tab.formulas().get(key));
        }

        Object columnFormula(String column, int row) {
            return value("k:" + column + "#" + row, "#" + row, tab.columnFormulas().get(column));
        }

        private Object value(String id, String ownRow, String stored) {
            if (memo.containsKey(id)) {
                return memo.get(id);
            }
            if (!running.add(id) || nesting >= MOST_NESTING) {
                return XlError.REF;
            }
            nesting++;
            try {
                Object result;
                if (System.nanoTime() > deadline) {
                    result = XlError.LIMIT;
                } else {
                    try {
                        Node tree = trees.computeIfAbsent(stored, s -> FormulaParser.parse(s, limits));
                        result = FormulaEvaluator.evaluate(tree, ownRow, this, limits);
                    } catch (FormulaRefused refused) {
                        // A stored formula was accepted at save; one that is refused now has been tampered with.
                        result = XlError.VALUE;
                    }
                }
                memo.put(id, result);
                return result;
            } finally {
                nesting--;
                running.remove(id);
            }
        }

        Answer answer(Object result) {
            if (result instanceof XlError error) {
                return new Answer(Kind.ERROR, error.code());
            }
            if (result instanceof Double d) {
                return new Answer(Kind.NUMBER, FormulaValues.numberText(d));
            }
            if (result instanceof Boolean b) {
                return new Answer(Kind.BOOLEAN, b ? "TRUE" : "FALSE");
            }
            return new Answer(Kind.TEXT, result == null ? "" : result.toString());
        }

        // ---- the cells a formula reads ----

        @Override
        public Object cell(String column, String row) {
            if (!columnAt.containsKey(column)) {
                return XlError.REF;
            }
            if (row.startsWith("#")) {
                int index = Integer.parseInt(row.substring(1));
                if (tab.columnFormulas().containsKey(column)) {
                    return columnFormula(column, index);
                }
                return index < inputs.rows().size() ? inputs.rows().get(index).get(column) : null;
            }
            String key = row + ":" + column;
            if (tab.formulas().containsKey(key)) {
                return cellFormula(key);
            }
            SheetGrid.Cell cell = tab.body().cells().get(key);
            boolean header = tab.body().headerRows().stream().anyMatch(line -> line.id().equals(row));
            if (cell != null && (header || SheetGridValidator.isFixed(cell, false))) {
                return cell.value() instanceof String text ? text : null;
            }
            if (inputs.cells().containsKey(key)) {
                return inputs.cells().get(key);
            }
            return cell != null && cell.value() instanceof String preset ? preset : null;
        }

        @Override
        public List<Object> range(String fromColumn, String fromRow, String toColumn, String toRow) {
            Integer c0 = columnAt.get(fromColumn);
            Integer c1 = columnAt.get(toColumn);
            Integer r0 = rowAt.get(fromRow);
            Integer r1 = rowAt.get(toRow);
            List<Object> out = new ArrayList<>();
            if (c0 == null || c1 == null || r0 == null || r1 == null) {
                out.add(XlError.REF);
                return out;
            }
            for (int r = Math.min(r0, r1); r <= Math.max(r0, r1); r++) {
                for (int c = Math.min(c0, c1); c <= Math.max(c0, c1); c++) {
                    out.add(cell(tab.body().columns().get(c).id(), order.get(r)));
                }
            }
            return out;
        }

        @Override
        public List<String> bodyRows() {
            return body;
        }
    }
}
