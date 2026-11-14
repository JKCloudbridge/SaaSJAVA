package app.platform.sheets.internal.formula;

import app.platform.sheets.internal.formula.FormulaValues.Signal;
import app.platform.sheets.internal.formula.FormulaValues.Test;
import app.platform.sheets.internal.formula.Node.Binary;
import app.platform.sheets.internal.formula.Node.Bool;
import app.platform.sheets.internal.formula.Node.Call;
import app.platform.sheets.internal.formula.Node.Group;
import app.platform.sheets.internal.formula.Node.Negate;
import app.platform.sheets.internal.formula.Node.Num;
import app.platform.sheets.internal.formula.Node.Percent;
import app.platform.sheets.internal.formula.Node.Range;
import app.platform.sheets.internal.formula.Node.Ref;
import app.platform.sheets.internal.formula.Node.Text;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Calculates a formula by interpreting its tree (SH2, ADR-0252). <strong>Nothing a person typed is ever run as
 * code</strong>:
 * the tree has five kinds of part, the functions are a fixed table, and every step is counted against a bound
 * ({@link FormulaLimits}). The server's answer is the one that counts; the browser has a copy of this class that only
 * shows a result while an author types, and both are held to one shared file of cases.
 *
 * <p>Values are {@code null} (an empty cell), {@link Double}, {@link String}, {@link Boolean} or {@link XlError}. An
 * error is a value: it is shown as its code and never hidden. A bound that is reached gives {@link XlError#LIMIT}.
 */
public final class FormulaEvaluator {

    /** Where the values come from. */
    public interface Grid {

        /**
         * The value of one cell.
         *
         * @param column the identifier of the column
         * @param row the identifier of the row, or a body row's token
         * @return the value: null, Double, String, Boolean or XlError
         */
        Object cell(String column, String row);

        /**
         * The values of a rectangle, row by row.
         *
         * @param fromColumn the first column
         * @param fromRow the first row
         * @param toColumn the second column
         * @param toRow the second row
         * @return the values in order
         */
        List<Object> range(String fromColumn, String fromRow, String toColumn, String toRow);

        /** The tokens of the body rows in order (empty for a sheet that names its rows). */
        List<String> bodyRows();
    }

    private record Cells(List<Object> values) {
    }

    private static final class LimitReached extends RuntimeException {
        private static final long serialVersionUID = 1L;

        LimitReached() {
            super("limit", null, false, false);
        }
    }

    private final Grid grid;
    private final String ownRow;
    private final FormulaLimits limits;
    private int steps;

    private FormulaEvaluator(Grid grid, String ownRow, FormulaLimits limits) {
        this.grid = grid;
        this.ownRow = ownRow;
        this.limits = limits;
    }

    /**
     * Calculates a tree.
     *
     * @param tree the formula
     * @param ownRow the row the formula is drawn at (what a reference without a row means)
     * @param grid the cells
     * @param limits the bounds
     * @return the value: a Double, String, Boolean or XlError (an empty answer is 0)
     */
    public static Object evaluate(Node tree, String ownRow, Grid grid, FormulaLimits limits) {
        FormulaEvaluator evaluator = new FormulaEvaluator(grid, ownRow, limits);
        try {
            Object result = evaluator.scalar(evaluator.eval(tree));
            return result == null ? Double.valueOf(0) : result;
        } catch (Signal signal) {
            return signal.error();
        } catch (LimitReached limit) {
            return XlError.LIMIT;
        }
    }

    // ---- the tree ----

    private void tick(int count) {
        steps += count;
        if (steps > limits.maxSteps()) {
            throw new LimitReached();
        }
    }

    /** The part inside any brackets around it: brackets decide the order of a calculation and nothing else. */
    private static Node bare(Node node) {
        Node n = node;
        while (n instanceof Group g) {
            n = g.inner();
        }
        return n;
    }

    private Object scalar(Object v) {
        if (v instanceof Cells range) {
            if (range.values().size() == 1) {
                return range.values().get(0);
            }
            return XlError.VALUE;
        }
        return v;
    }

    private Object eval(Node node) {
        tick(1);
        return switch (node) {
            case Num n -> n.value();
            case Text t -> t.value();
            case Bool b -> b.value();
            case Group g -> eval(g.inner());
            case Ref r -> reference(r);
            case Range r -> range(r);
            case Negate n -> {
                try {
                    yield -FormulaValues.number(scalar(eval(n.operand())));
                } catch (Signal s) {
                    yield s.error();
                }
            }
            case Percent p -> {
                try {
                    yield FormulaValues.number(scalar(eval(p.operand()))) / 100;
                } catch (Signal s) {
                    yield s.error();
                }
            }
            case Binary b -> binary(b);
            case Call c -> call(c);
        };
    }

    private Object reference(Ref r) {
        if (r.growing()) {
            return growing(r.column());
        }
        return grid.cell(r.column(), r.row() == null ? ownRow : r.row());
    }

    private Object growing(String column) {
        List<String> body = grid.bodyRows();
        if (body.isEmpty()) {
            return new Cells(List.of());
        }
        List<Object> values = grid.range(column, body.get(0), column, body.get(body.size() - 1));
        tick(values.size());
        return new Cells(values);
    }

    private Object range(Range r) {
        if (r.from().growing() || r.to().growing()) {
            List<String> body = grid.bodyRows();
            String first = r.from().growing() ? (body.isEmpty() ? null : body.get(0))
                    : r.from().row() == null ? ownRow : r.from().row();
            String last = r.to().growing() ? (body.isEmpty() ? null : body.get(body.size() - 1))
                    : r.to().row() == null ? ownRow : r.to().row();
            if (first == null || last == null) {
                return new Cells(List.of());
            }
            List<Object> values = grid.range(r.from().column(), first, r.to().column(), last);
            tick(values.size());
            return new Cells(values);
        }
        List<Object> values = grid.range(r.from().column(), r.from().row() == null ? ownRow : r.from().row(),
                r.to().column(), r.to().row() == null ? ownRow : r.to().row());
        tick(values.size());
        return new Cells(values);
    }

    private Object binary(Binary b) {
        Object left = scalar(eval(b.left()));
        Object right = scalar(eval(b.right()));
        try {
            switch (b.operator()) {
                case "=", "<>", "<", "<=", ">", ">=" -> {
                    int c = FormulaValues.compare(left, right);
                    return switch (b.operator()) {
                        case "=" -> c == 0;
                        case "<>" -> c != 0;
                        case "<" -> c < 0;
                        case "<=" -> c <= 0;
                        case ">" -> c > 0;
                        default -> c >= 0;
                    };
                }
                default -> {
                    double x = FormulaValues.number(left);
                    double y = FormulaValues.number(right);
                    return switch (b.operator()) {
                        case "+" -> FormulaValues.finite(x + y);
                        case "-" -> FormulaValues.finite(x - y);
                        case "*" -> FormulaValues.finite(x * y);
                        case "/" -> {
                            if (y == 0) {
                                throw new Signal(XlError.DIV0);
                            }
                            yield FormulaValues.finite(x / y);
                        }
                        default -> FormulaValues.power(x, y);
                    };
                }
            }
        } catch (Signal s) {
            return s.error();
        }
    }

    // ---- reading arguments ----

    private double num(Node node) {
        return FormulaValues.number(scalar(eval(node)));
    }

    /** The numbers an aggregate reads: in a reference or range only numbers count; a direct argument is converted. */
    private List<Double> numbersOf(List<Node> args) {
        List<Double> out = new ArrayList<>();
        for (Node a : args) {
            Object v = eval(a);
            if (v instanceof Cells range) {
                for (Object c : range.values()) {
                    if (c instanceof XlError e) {
                        throw new Signal(e);
                    }
                    if (c instanceof Double d) {
                        out.add(d);
                    }
                }
            } else if (bare(a) instanceof Ref) {
                if (v instanceof XlError e) {
                    throw new Signal(e);
                }
                if (v instanceof Double d) {
                    out.add(d);
                }
            } else {
                out.add(FormulaValues.number(v));
            }
        }
        return out;
    }

    private List<Boolean> logicalsOf(List<Node> args) {
        List<Boolean> out = new ArrayList<>();
        for (Node a : args) {
            Object v = eval(a);
            List<Object> items = v instanceof Cells range ? range.values() : Arrays.asList(v);
            boolean fromCells = bare(a) instanceof Ref || v instanceof Cells;
            for (Object c : items) {
                if (c instanceof XlError e) {
                    throw new Signal(e);
                }
                if (fromCells && (c instanceof String || c == null)) {
                    continue;
                }
                out.add(FormulaValues.bool(c));
            }
        }
        if (out.isEmpty()) {
            throw new Signal(XlError.VALUE);
        }
        return out;
    }

    private List<Object> rangeArg(Node node) {
        Object v = eval(node);
        if (v instanceof Cells range) {
            return range.values();
        }
        if (bare(node) instanceof Ref) {
            return Arrays.asList(v);
        }
        throw new Signal(XlError.VALUE);
    }

    private Object conditional(List<Node> a, String kind) {
        List<Object> range = rangeArg(a.get(0));
        Test test = FormulaValues.criterion(scalar(eval(a.get(1))));
        List<Object> target = a.size() == 3 ? rangeArg(a.get(2)) : range;
        int count = 0;
        double sum = 0;
        int n = 0;
        for (int i = 0; i < range.size(); i++) {
            Object cell = range.get(i);
            if (cell instanceof XlError || !test.matches(cell)) {
                continue;
            }
            count++;
            Object t = i < target.size() ? target.get(i) : null;
            if (t instanceof Double d) {
                sum += d;
                n++;
            }
        }
        if (kind.equals("COUNTIF")) {
            return (double) count;
        }
        if (kind.equals("SUMIF")) {
            return sum;
        }
        if (n == 0) {
            throw new Signal(XlError.DIV0);
        }
        return sum / n;
    }

    // ---- the functions ----

    private Object call(Call n) {
        try {
            return function(n);
        } catch (Signal s) {
            return s.error();
        }
    }

    private Object function(Call n) {
        List<Node> a = n.arguments();
        switch (n.name()) {
            case "IF" -> {
                if (FormulaValues.bool(scalar(eval(a.get(0))))) {
                    return scalar(eval(a.get(1)));
                }
                return a.size() == 3 ? scalar(eval(a.get(2))) : Boolean.FALSE;
            }
            case "IFERROR" -> {
                Object v = firstValue(a.get(0));
                return v instanceof XlError ? scalar(eval(a.get(1))) : v;
            }
            case "IFS" -> {
                if (a.size() % 2 != 0) {
                    return XlError.NA;
                }
                for (int i = 0; i < a.size(); i += 2) {
                    if (FormulaValues.bool(scalar(eval(a.get(i))))) {
                        return scalar(eval(a.get(i + 1)));
                    }
                }
                return XlError.NA;
            }
            case "SWITCH" -> {
                Object x = scalar(eval(a.get(0)));
                if (x instanceof XlError) {
                    return x;
                }
                int pairs = (a.size() - 1) / 2;
                boolean hasDefault = (a.size() - 1) % 2 != 0;
                for (int i = 0; i < pairs; i++) {
                    Object candidate = scalar(eval(a.get(1 + 2 * i)));
                    if (!(candidate instanceof XlError) && FormulaValues.compare(x, candidate) == 0) {
                        return scalar(eval(a.get(2 + 2 * i)));
                    }
                }
                return hasDefault ? scalar(eval(a.get(a.size() - 1))) : XlError.NA;
            }
            case "ABS" -> {
                return Math.abs(num(a.get(0)));
            }
            case "INT" -> {
                return Math.floor(num(a.get(0)));
            }
            case "SQRT" -> {
                double x = num(a.get(0));
                if (x < 0) {
                    throw new Signal(XlError.NUM);
                }
                return Math.sqrt(x);
            }
            case "MOD" -> {
                double x = num(a.get(0));
                double d = num(a.get(1));
                if (d == 0) {
                    throw new Signal(XlError.DIV0);
                }
                return FormulaValues.finite(x - d * Math.floor(x / d));
            }
            case "POWER" -> {
                double x = num(a.get(0));
                double y = num(a.get(1));
                return FormulaValues.power(x, y);
            }
            case "ROUND", "ROUNDUP", "ROUNDDOWN" -> {
                double x = num(a.get(0));
                double d = num(a.get(1));
                return switch (n.name()) {
                    case "ROUND" -> FormulaValues.roundHalfUp(x, d);
                    case "ROUNDUP" -> FormulaValues.roundAway(x, d);
                    default -> FormulaValues.roundToward(x, d);
                };
            }
            case "PRODUCT" -> {
                List<Double> x = numbersOf(a);
                double product = 1;
                for (double v : x) {
                    product = FormulaValues.finite(product * v);
                }
                return x.isEmpty() ? 0.0 : product;
            }
            case "SUM" -> {
                double sum = 0;
                for (double v : numbersOf(a)) {
                    sum = FormulaValues.finite(sum + v);
                }
                return sum;
            }
            case "MIN" -> {
                List<Double> x = numbersOf(a);
                return x.isEmpty() ? 0.0 : x.stream().mapToDouble(Double::doubleValue).min().getAsDouble();
            }
            case "MAX" -> {
                List<Double> x = numbersOf(a);
                return x.isEmpty() ? 0.0 : x.stream().mapToDouble(Double::doubleValue).max().getAsDouble();
            }
            case "AVERAGE" -> {
                List<Double> x = numbersOf(a);
                if (x.isEmpty()) {
                    throw new Signal(XlError.DIV0);
                }
                double sum = 0;
                for (double v : x) {
                    sum = FormulaValues.finite(sum + v);
                }
                return sum / x.size();
            }
            case "MEDIAN" -> {
                List<Double> x = numbersOf(a);
                if (x.isEmpty()) {
                    throw new Signal(XlError.NUM);
                }
                double[] sorted = x.stream().mapToDouble(Double::doubleValue).sorted().toArray();
                int m = sorted.length >> 1;
                return sorted.length % 2 == 1 ? sorted[m] : (sorted[m - 1] + sorted[m]) / 2;
            }
            case "MODE" -> {
                List<Double> x = numbersOf(a);
                java.util.Map<Double, Integer> frequency = new java.util.HashMap<>();
                for (double v : x) {
                    frequency.merge(v == 0 ? 0.0 : v, 1, Integer::sum);
                }
                Double best = null;
                int top = 1;
                for (double v : x) {
                    int f = frequency.get(v == 0 ? 0.0 : v);
                    if (f > top) {
                        top = f;
                        best = v;
                    }
                }
                if (best == null) {
                    throw new Signal(XlError.NA);
                }
                return best;
            }
            case "COUNT" -> {
                int count = 0;
                for (Node arg : a) {
                    Object v = eval(arg);
                    for (Object cell : v instanceof Cells range ? range.values() : Arrays.asList(v)) {
                        if (cell instanceof Double) {
                            count++;
                        }
                    }
                }
                return (double) count;
            }
            case "COUNTA" -> {
                int count = 0;
                for (Node arg : a) {
                    Object v = eval(arg);
                    for (Object cell : v instanceof Cells range ? range.values() : Arrays.asList(v)) {
                        if (cell != null) {
                            count++;
                        }
                    }
                }
                return (double) count;
            }
            case "LARGE", "SMALL" -> {
                List<Object> r = rangeArg(a.get(0));
                List<Double> xs = new ArrayList<>();
                for (Object c : r) {
                    if (c instanceof XlError e) {
                        throw new Signal(e);
                    }
                    if (c instanceof Double d) {
                        xs.add(d);
                    }
                }
                double k = num(a.get(1));
                int kk = (int) Math.max(Integer.MIN_VALUE / 2, Math.min(Integer.MAX_VALUE / 2, Math.ceil(k)));
                if (kk < 1 || kk > xs.size()) {
                    throw new Signal(XlError.NUM);
                }
                double[] sorted = xs.stream().mapToDouble(Double::doubleValue).sorted().toArray();
                return n.name().equals("LARGE") ? sorted[sorted.length - kk] : sorted[kk - 1];
            }
            case "AND" -> {
                return logicalsOf(a).stream().allMatch(Boolean::booleanValue);
            }
            case "OR" -> {
                return logicalsOf(a).stream().anyMatch(Boolean::booleanValue);
            }
            case "NOT" -> {
                return !FormulaValues.bool(scalar(eval(a.get(0))));
            }
            case "ISBLANK" -> {
                if (!(bare(a.get(0)) instanceof Ref ref) || ref.growing()) {
                    return Boolean.FALSE;
                }
                return eval(a.get(0)) == null;
            }
            case "ISNUMBER" -> {
                return scalar(eval(a.get(0))) instanceof Double;
            }
            case "ISTEXT" -> {
                return scalar(eval(a.get(0))) instanceof String;
            }
            case "SUMIF", "COUNTIF", "AVERAGEIF" -> {
                return conditional(a, n.name());
            }
            case "VALUE" -> {
                return value(scalar(eval(a.get(0))));
            }
            case "TEXT" -> {
                Object v = scalar(eval(a.get(0)));
                if (v instanceof XlError) {
                    return v;
                }
                if (v instanceof String) {
                    return v;
                }
                if (v instanceof Boolean b) {
                    return b ? "TRUE" : "FALSE";
                }
                double x = v == null ? 0 : (Double) v;
                return FormulaValues.formatNumber(x, ((Text) a.get(1)).value());
            }
            case "CONCATENATE" -> {
                StringBuilder out = new StringBuilder();
                for (Node arg : a) {
                    Object v = eval(arg);
                    if (v instanceof Cells) {
                        return XlError.VALUE;
                    }
                    out.append(FormulaValues.text(v));
                    if (out.length() > limits.maxTextChars()) {
                        throw new LimitReached();
                    }
                }
                return out.toString();
            }
            case "LEFT", "RIGHT" -> {
                String t = FormulaValues.text(scalar(eval(a.get(0))));
                double k = a.size() == 2 ? num(a.get(1)) : 1;
                if (k < 0) {
                    throw new Signal(XlError.VALUE);
                }
                int c = (int) Math.min(Integer.MAX_VALUE, Math.floor(k));
                if (n.name().equals("LEFT")) {
                    return t.substring(0, Math.min(c, t.length()));
                }
                return c == 0 ? "" : t.substring(Math.max(0, t.length() - c));
            }
            case "LEN" -> {
                String t = FormulaValues.text(scalar(eval(a.get(0))));
                return (double) t.codePointCount(0, t.length());
            }
            case "UPPER" -> {
                return FormulaValues.text(scalar(eval(a.get(0)))).toUpperCase(Locale.ROOT);
            }
            case "TRIM" -> {
                return FormulaValues.trim(FormulaValues.text(scalar(eval(a.get(0)))).replaceAll(" +", " "));
            }
            default -> {
                return XlError.NAME;
            }
        }
    }

    /** The scalar value of an argument, with an error kept as a value (not thrown). */
    private Object firstValue(Node node) {
        try {
            return scalar(eval(node));
        } catch (Signal s) {
            return s.error();
        }
    }

    private static Object value(Object v) {
        if (v instanceof XlError) {
            return v;
        }
        if (v == null) {
            return 0.0;
        }
        if (v instanceof Double) {
            return v;
        }
        if (v instanceof Boolean) {
            return XlError.VALUE;
        }
        String s = FormulaValues.trim((String) v);
        if (s.endsWith("%")) {
            String body = s.substring(0, s.length() - 1).replace(",", "");
            return FormulaValues.isPercentBody(body) ? (Object) (Double.parseDouble(body) / 100) : XlError.VALUE;
        }
        try {
            return FormulaValues.number(s);
        } catch (Signal e) {
            return e.error();
        }
    }
}
