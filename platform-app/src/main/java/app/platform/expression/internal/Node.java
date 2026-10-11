package app.platform.expression.internal;

import app.platform.expression.ExpressionType;
import java.util.List;

/**
 * The tree of an expression: a closed set of five kinds of part, so that everything that walks the tree (the checker,
 * the evaluator, the printer) is a {@code switch} the compiler checks. There is no part for "call a method", "read a
 * property" or "run text": the grammar cannot express them, so there is nothing to escape from.
 *
 * <p>Every part knows the offset of the character it starts at (a binary part: of its operator), only to name the
 * place of a problem. The offset takes part in equality, so a test that compares trees of different texts compares
 * {@link Printer#print(Node)} instead.
 */
public sealed interface Node permits Node.Literal, Node.FieldRef, Node.Unary, Node.Binary, Node.Call {

    /** The index of the character the part starts at. */
    int offset();

    /**
     * A value written in the text. A number is a normalised decimal, a text a string that is never empty (the empty
     * text is no value), a boolean a {@link Boolean}; {@code NULL} and the empty text have the value {@code null}.
     */
    record Literal(ExpressionType type, Object value, int offset) implements Node {
    }

    /** A field of the same object, by API name. */
    record FieldRef(String name, int offset) implements Node {
    }

    /** The prefix operators. */
    enum UnaryOp { NEGATE, NOT }

    /** A prefix operator applied to a part. */
    record Unary(UnaryOp op, Node operand, int offset) implements Node {
    }

    /** The infix operators. */
    enum BinaryOp {
        ADD("+"), SUBTRACT("-"), MULTIPLY("*"), DIVIDE("/"), REMAINDER("%"),
        EQUAL("="), NOT_EQUAL("!="), LESS("<"), LESS_OR_EQUAL("<="), GREATER(">"), GREATER_OR_EQUAL(">="),
        AND("AND"), OR("OR");

        private final String symbol;

        BinaryOp(String symbol) {
            this.symbol = symbol;
        }

        /** How the operator is written. */
        public String symbol() {
            return symbol;
        }

        /** Whether it compares two values. */
        public boolean isComparison() {
            return this == EQUAL || this == NOT_EQUAL || this == LESS || this == LESS_OR_EQUAL || this == GREATER
                    || this == GREATER_OR_EQUAL;
        }
    }

    /** An infix operator applied to two parts. */
    record Binary(BinaryOp op, Node left, Node right, int offset) implements Node {
    }

    /** A call of a function of the fixed library; {@code function} is the upper case name. */
    record Call(String function, List<Node> arguments, int offset) implements Node {

        /** Copies the arguments. */
        public Call {
            arguments = List.copyOf(arguments);
        }
    }
}
