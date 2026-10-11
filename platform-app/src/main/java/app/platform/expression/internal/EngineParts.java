package app.platform.expression.internal;

import app.platform.expression.ExpressionType;
import app.platform.expression.FunctionInfo;
import app.platform.expression.Limits;
import app.platform.expression.Purpose;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The one door from the public classes of the module into its internals: it is public only so that
 * {@code ExpressionEngine} in the root package can call it, and nothing outside the module may (the architecture
 * tests keep every other module out of the internal packages). The lexer, parser, checker, evaluator and library stay
 * unreachable behind it.
 */
public final class EngineParts {

    /**
     * What a check found.
     *
     * @param type the type the expression gives
     * @param fields the fields it uses, alphabetical
     * @param usesClock whether it reads the moment of the evaluation
     */
    public record Checked(ExpressionType type, List<String> fields, boolean usesClock) {

        /** Copies the list. */
        public Checked {
            fields = List.copyOf(fields);
        }
    }

    private EngineParts() {
    }

    /** The tree of a text. */
    public static Node parse(String source, Limits limits) {
        return new Parser(source, new Lexer(source, limits).tokens(), limits).parse();
    }

    /** How many parts the tree has. */
    public static int size(Node node) {
        return switch (node) {
            case Node.Literal literal -> 1;
            case Node.FieldRef field -> 1;
            case Node.Unary unary -> 1 + size(unary.operand());
            case Node.Binary binary -> 1 + size(binary.left()) + size(binary.right());
            case Node.Call call -> 1 + call.arguments().stream().mapToInt(EngineParts::size).sum();
        };
    }

    /** Where the root part of the tree is in the text. */
    public static int rootOffset(Node root) {
        return root.offset();
    }

    /** Checks the tree against the fields. */
    public static Checked check(String source, Node root, Map<String, ExpressionType> schema, Purpose purpose) {
        Checker.Result result = new Checker(source, schema, purpose).check(root);
        return new Checked(result.type(), result.fields(), result.usesClock());
    }

    /** Refuses what the purpose does not allow, without needing the fields (the clock in a formula). */
    public static void checkUse(String source, Node root, Purpose purpose) {
        new Checker(source, Map.of(), purpose).checkUse(root);
    }

    /** Evaluates the tree. */
    public static Object evaluate(String source, Node root, Map<String, ExpressionType> types,
            Map<String, Object> values, Limits limits, Instant now, LongSupplier nanos) {
        return new Evaluator(source, types, values, limits, now, nanos).eval(root);
    }

    /** Reads a value written as text. */
    public static Object parseValue(ExpressionType type, String written, Limits limits, String field) {
        return Values.parse(type, written, limits, field);
    }

    /** Writes a value as text. */
    public static String formatValue(Object value) {
        return Values.format(value);
    }

    /** The canonical text of a tree. */
    public static String print(Node root) {
        return Printer.print(root);
    }

    /** The library, described. */
    public static List<FunctionInfo> functions() {
        return Functions.all().stream().map(function -> new FunctionInfo(function.name(), function.signature(),
                function.description(), function.clock())).toList();
    }
}
