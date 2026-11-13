package app.platform.sheets.internal.formula;

import java.util.List;

/**
 * The tree a formula is parsed into (SH2, ADR-0252): five kinds of part and nothing else. The tree is interpreted,
 * never turned into code. Every type prints as a redacted marker, because a formula is text of the organization.
 */
public sealed interface Node {

    /**
     * A number.
     *
     * @param value the value
     * @param text the one canonical spelling of the digits
     */
    record Num(double value, String text) implements Node {
        @Override
        public String toString() {
            return "Num[redacted]";
        }
    }

    /**
     * A text constant.
     *
     * @param value the text
     */
    record Text(String value) implements Node {
        @Override
        public String toString() {
            return "Text[redacted]";
        }
    }

    /**
     * TRUE or FALSE.
     *
     * @param value the value
     */
    record Bool(boolean value) implements Node {
        @Override
        public String toString() {
            return "Bool[redacted]";
        }
    }

    /**
     * A reference to one cell, or, when {@code growing}, to a column over every body row.
     *
     * @param column the identifier of the column
     * @param row the identifier of the row, or null for the formula's own row (and for a growing reference)
     * @param absoluteColumn whether the column was written with an absolute marker
     * @param absoluteRow whether the row was written with an absolute marker
     * @param growing whether this is the column over every body row, however many the filler adds
     */
    record Ref(String column, String row, boolean absoluteColumn, boolean absoluteRow, boolean growing)
            implements Node {
        @Override
        public String toString() {
            return "Ref[redacted]";
        }
    }

    /**
     * A range between two references.
     *
     * @param from the first reference
     * @param to the second reference
     */
    record Range(Ref from, Ref to) implements Node {
        @Override
        public String toString() {
            return "Range[redacted]";
        }
    }

    /**
     * A minus sign in front of a part.
     *
     * @param operand the part
     */
    record Negate(Node operand) implements Node {
        @Override
        public String toString() {
            return "Negate[redacted]";
        }
    }

    /**
     * A percent sign after a part.
     *
     * @param operand the part
     */
    record Percent(Node operand) implements Node {
        @Override
        public String toString() {
            return "Percent[redacted]";
        }
    }

    /**
     * An operator between two parts.
     *
     * @param operator one of {@code + - * / ^ = <> < <= > >=}
     * @param left the left part
     * @param right the right part
     */
    record Binary(String operator, Node left, Node right) implements Node {
        @Override
        public String toString() {
            return "Binary[redacted]";
        }
    }

    /**
     * A part in brackets as the author wrote it.
     *
     * @param inner the part
     */
    record Group(Node inner) implements Node {
        @Override
        public String toString() {
            return "Group[redacted]";
        }
    }

    /**
     * A call of one of the allowed functions.
     *
     * @param name the name in capitals
     * @param arguments the arguments
     */
    record Call(String name, List<Node> arguments) implements Node {
        /** Copies the list. */
        public Call {
            arguments = List.copyOf(arguments);
        }

        @Override
        public String toString() {
            return "Call[redacted]";
        }
    }
}
