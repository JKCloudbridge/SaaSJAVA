package app.platform.expression;

import app.platform.expression.ExpressionException.Kind;
import app.platform.expression.internal.EngineParts;
import app.platform.expression.internal.Node;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The expression engine (ADR-0076): parse a text into a tree, check the tree against the fields of an object, evaluate
 * it over a map of values. It is the only door into the module. It holds only its {@link Limits}, so one instance is
 * shared by every thread.
 *
 * <pre>
 *   Compiled rule = engine.compile("Stage = 'Closed' AND ISBLANK(closeDate)", fields, Purpose.VALIDATION_RULE);
 *   Object rejects = engine.evaluate(rule, Map.of("Stage", "Closed"), clock.instant());
 * </pre>
 *
 * <p>What it promises: nothing the text says can reach anything but the values it was given and the fixed library of
 * functions; a bound that is reached is an {@link ExpressionException} that names the bound; every failure is an
 * {@link ExpressionException} and nothing else; the same text and values give the same answer every time; evaluating
 * costs no database access, no I/O and no clock (the moment is an argument).
 */
public final class ExpressionEngine {

    private final Limits limits;
    private final LongSupplier nanos;

    /** An engine with the given bounds. */
    public ExpressionEngine(Limits limits) {
        this(limits, System::nanoTime);
    }

    /** An engine with a clock of its own for the time bound (tests make time pass at will). */
    ExpressionEngine(Limits limits, LongSupplier nanos) {
        this.limits = Objects.requireNonNull(limits);
        this.nanos = Objects.requireNonNull(nanos);
    }

    /** The bounds this engine enforces. */
    public Limits limits() {
        return limits;
    }

    // ---- parsing and checking ----

    /**
     * Parses a text into a tree: proves that it follows the grammar and the bounds, and nothing more.
     *
     * @throws ExpressionException {@code SYNTAX}, {@code TOO_LONG}, {@code TOO_MANY_NODES}, {@code TOO_DEEP},
     *         {@code OVERFLOW} (a number literal) or {@code TEXT_TOO_LONG}, with the place
     */
    public Expression parse(String text) {
        String source = text == null ? "" : text;
        Node root = EngineParts.parse(source, limits);
        return new Expression(source, root, EngineParts.size(root));
    }

    /**
     * Checks a parsed expression against the fields it may use and for what it is used.
     *
     * @param fields the type of every field the expression may name, by API name
     * @throws ExpressionException {@code UNKNOWN_FIELD}, {@code UNKNOWN_FUNCTION}, {@code ARGUMENTS}, {@code TYPE} or
     *         {@code NOT_ALLOWED}, with the place
     */
    public Compiled check(Expression expression, Map<String, ExpressionType> fields, Purpose purpose) {
        Map<String, ExpressionType> schema = Map.copyOf(fields);
        EngineParts.Checked checked = EngineParts.check(expression.source(), expression.root(), schema, purpose);
        Map<String, ExpressionType> used = new HashMap<>();
        checked.fields().forEach(name -> used.put(name, schema.get(name)));
        return new Compiled(expression, checked.type(), purpose, checked.fields(), checked.usesClock(), used);
    }

    /**
     * Refuses what the purpose does not allow without needing the fields: the clock in a formula. It lets a formula
     * be refused the moment it is saved, before the other fields are looked at.
     *
     * @throws ExpressionException {@code NOT_ALLOWED}, with the place
     */
    public void checkUse(Expression expression, Purpose purpose) {
        EngineParts.checkUse(expression.source(), expression.root(), purpose);
    }

    /** Parses and checks in one step. */
    public Compiled compile(String text, Map<String, ExpressionType> fields, Purpose purpose) {
        return check(parse(text), fields, purpose);
    }

    /**
     * Parses and checks, and also requires the expression to give the expected type: a formula whose field says it
     * gives a number must give a number. A number fits every numeric field type; the word NULL alone fits none.
     *
     * @param expected the type the expression must give, or null for any
     */
    public Compiled compile(String text, Map<String, ExpressionType> fields, Purpose purpose,
            ExpressionType expected) {
        Compiled compiled = compile(text, fields, purpose);
        if (expected != null && compiled.type() != expected) {
            throw new ExpressionException(Kind.TYPE, ExpressionException.Position.of(compiled.expression().source(),
                    EngineParts.rootOffset(compiled.expression().root())), "This gives " + compiled.type().words()
                            + ", but it is declared to give " + expected.words() + ".");
        }
        return compiled;
    }

    // ---- evaluating ----

    /**
     * Evaluates a checked expression over the values of a record. A field with no entry, a null or the empty text has
     * no value. A value of the wrong kind for its field is refused.
     *
     * @param values the values by field API name: {@code BigDecimal} (or a whole number type), {@code String},
     *        {@code Boolean}, {@code LocalDate}, {@code Instant} or {@code LocalTime}
     * @param now the moment {@code TODAY()} and {@code NOW()} give (rules only)
     * @return the value ({@code BigDecimal}, {@code String}, {@code Boolean}, {@code LocalDate}, {@code Instant} or
     *         {@code LocalTime}), or null for no value
     * @throws ExpressionException {@code DIVISION_BY_ZERO}, {@code OVERFLOW}, {@code TEXT_TOO_LONG},
     *         {@code TOO_MANY_STEPS}, {@code TOO_SLOW}, {@code INVALID_VALUE}, {@code NOT_A_NUMBER} or
     *         {@code OUT_OF_RANGE}, with the place
     */
    public Object evaluate(Compiled compiled, Map<String, Object> values, Instant now) {
        Objects.requireNonNull(now);
        Map<String, Object> given = values == null ? Map.of() : values;
        return EngineParts.evaluate(compiled.expression().source(), compiled.expression().root(),
                compiled.fieldTypes(), given, limits, now, nanos);
    }

    // ---- values as text ----

    /**
     * Reads the text a person typed as a value of a type (a number in plain digits, true or false, a date like
     * 2030-01-31, a date and time like 2030-01-31T09:30:00Z, a time like 09:30). Blank text is no value.
     *
     * @param field the name of the field, for the message
     * @throws ExpressionException {@code INVALID_VALUE}
     */
    public Object parseValue(ExpressionType type, String written, String field) {
        return EngineParts.parseValue(type, written, limits, field);
    }

    /** A value as text: plain digits, true or false, ISO dates and times; null for no value. */
    public String formatValue(Object value) {
        return EngineParts.formatValue(value);
    }

    // ---- printing and the library ----

    /** The canonical text of the tree: operator parts in brackets, capital function names. */
    public String print(Expression expression) {
        return EngineParts.print(expression.root());
    }

    /** The functions of the library, in the order of the guide. */
    public static List<FunctionInfo> functions() {
        return EngineParts.functions();
    }
}
