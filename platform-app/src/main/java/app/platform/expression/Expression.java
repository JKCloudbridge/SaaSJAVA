package app.platform.expression;

import app.platform.expression.internal.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * An expression that has been parsed: the text and its tree, not yet checked against any field. It is immutable and
 * safe to share. Parsing proves that the text follows the grammar and the bounds; whether the fields exist and the
 * types fit is the next step ({@link ExpressionEngine#check}).
 */
public final class Expression {

    private final String source;
    private final Node root;
    private final int size;

    Expression(String source, Node root, int size) {
        this.source = source;
        this.root = root;
        this.size = size;
    }

    /** The text as it was written. It belongs to the organization: never log it. */
    public String source() {
        return source;
    }

    /** How many parts (values, fields, operators, calls) the tree has. */
    public int size() {
        return size;
    }

    /** The names of the fields the expression uses, each once, in alphabetical order. */
    public List<String> fields() {
        TreeSet<String> names = new TreeSet<>();
        collect(root, names);
        return new ArrayList<>(names);
    }

    Node root() {
        return root;
    }

    private static void collect(Node node, TreeSet<String> names) {
        if (node instanceof Node.FieldRef field) {
            names.add(field.name());
        } else if (node instanceof Node.Unary unary) {
            collect(unary.operand(), names);
        } else if (node instanceof Node.Binary binary) {
            collect(binary.left(), names);
            collect(binary.right(), names);
        } else if (node instanceof Node.Call call) {
            call.arguments().forEach(argument -> collect(argument, names));
        }
    }

    /** Prints no text: the expression belongs to the organization (ADR-0012). */
    @Override
    public String toString() {
        return "Expression[" + size + " parts]";
    }
}
