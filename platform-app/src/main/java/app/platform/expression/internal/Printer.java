package app.platform.expression.internal;

import java.math.BigDecimal;
import java.util.List;

/**
 * Writes a tree as text in one canonical form: every operator part in brackets, upper case function names, single
 * quotes with the quote doubled. The text it writes parses to a tree of the same shape, which is a property the tests
 * check on thousands of random trees (and which makes the printed form the way to compare two trees).
 */
final class Printer {

    private Printer() {
    }

    /** The canonical text of the tree. */
    static String print(Node node) {
        return write(new StringBuilder(), node).toString();
    }

    private static StringBuilder write(StringBuilder out, Node node) {
        return switch (node) {
            case Node.Literal literal -> literal(out, literal);
            case Node.FieldRef field -> out.append(field.name());
            case Node.Unary unary -> unary(out, unary);
            case Node.Binary binary -> binary(out, binary);
            case Node.Call call -> call(out, call);
        };
    }

    /**
     * A minus sign binds tighter than any operator, so {@code -(x)} stands anywhere; NOT binds looser than a
     * comparison, so it is written in brackets of its own, which is the one place it may stand inside an operator.
     */
    private static StringBuilder unary(StringBuilder out, Node.Unary unary) {
        if (unary.op() == Node.UnaryOp.NEGATE) {
            out.append("-(");
            return write(out, unary.operand()).append(')');
        }
        out.append("(NOT (");
        return write(out, unary.operand()).append("))");
    }

    private static StringBuilder binary(StringBuilder out, Node.Binary binary) {
        out.append('(');
        write(out, binary.left());
        out.append(' ').append(binary.op().symbol()).append(' ');
        return write(out, binary.right()).append(')');
    }

    private static StringBuilder call(StringBuilder out, Node.Call call) {
        out.append(call.function()).append('(');
        List<Node> arguments = call.arguments();
        for (int i = 0; i < arguments.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            write(out, arguments.get(i));
        }
        return out.append(')');
    }

    private static StringBuilder literal(StringBuilder out, Node.Literal literal) {
        return switch (literal.type()) {
            case TEXT -> {
                out.append('\'');
                if (literal.value() != null) {
                    out.append(((String) literal.value()).replace("'", "''"));
                }
                yield out.append('\'');
            }
            case NUMBER -> out.append(Numbers.format((BigDecimal) literal.value()));
            case BOOLEAN -> out.append(Boolean.TRUE.equals(literal.value()) ? "TRUE" : "FALSE");
            default -> out.append("NULL");
        };
    }
}
