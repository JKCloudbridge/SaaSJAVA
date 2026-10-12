package app.platform.expression;

import java.util.List;
import java.util.Map;

/**
 * An expression that was parsed and checked against the fields of an object: ready to evaluate, with what the check
 * found. It is immutable and safe to share between threads, which is what lets the metadata cache keep one per formula
 * and rule. It keeps the text and a tree of at most the bounded number of parts, and the types of the fields it uses,
 * nothing else.
 */
public final class Compiled {

    private final Expression expression;
    private final ExpressionType type;
    private final Purpose purpose;
    private final List<String> fields;
    private final boolean usesClock;
    private final Map<String, ExpressionType> fieldTypes;

    Compiled(Expression expression, ExpressionType type, Purpose purpose, List<String> fields, boolean usesClock,
            Map<String, ExpressionType> fieldTypes) {
        this.expression = expression;
        this.type = type;
        this.purpose = purpose;
        this.fields = List.copyOf(fields);
        this.usesClock = usesClock;
        this.fieldTypes = Map.copyOf(fieldTypes);
    }

    /** The parsed expression. */
    public Expression expression() {
        return expression;
    }

    /** The type the expression gives (never {@code NONE} for a rule; a lone NULL gives {@code NONE}). */
    public ExpressionType type() {
        return type;
    }

    /** What it was checked for. */
    public Purpose purpose() {
        return purpose;
    }

    /** The fields it uses, each once, in alphabetical order. */
    public List<String> fields() {
        return fields;
    }

    /** Whether it asks for the moment of the evaluation ({@code TODAY} or {@code NOW}). */
    public boolean usesClock() {
        return usesClock;
    }

    /** The types of the fields it uses, as they were when it was checked. */
    public Map<String, ExpressionType> fieldTypes() {
        return fieldTypes;
    }

    /** Prints no text: the expression belongs to the organization (ADR-0012). */
    @Override
    public String toString() {
        return "Compiled[" + type + ", " + fields.size() + " fields]";
    }
}
