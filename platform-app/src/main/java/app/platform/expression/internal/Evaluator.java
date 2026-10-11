package app.platform.expression.internal;

import app.platform.expression.ExpressionException;
import app.platform.expression.ExpressionException.Kind;
import app.platform.expression.ExpressionType;
import app.platform.expression.Limits;
import app.platform.expression.internal.Node.BinaryOp;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Evaluates a checked tree over a map of values (ADR-0076). It touches nothing outside its arguments: no database, no
 * clock (the moment is given), no file, no class. One evaluator makes one run and is thrown away.
 *
 * <p>Every part costs one step and some functions cost more (a long text), so the run is bounded by
 * {@link Limits#maxSteps()}; the clock is read at every step and the run stops at {@link Limits#maxMillis()}. Short
 * circuiting is part of the language: {@code IF} runs only the branch it takes and {@code AND}, {@code OR} and
 * {@code COALESCE} stop as soon as the answer is known, so {@code IF(b = 0, 0, a / b)} never divides by zero.
 *
 * <p>No value is "no value" ({@code null}); how each operator treats it is written in the metadata guide and kept in
 * one place, here: an operation with no value in it gives no value, except the comparisons with the word NULL, the
 * functions that exist to handle it, and the logical operators, which use three-valued logic.
 */
final class Evaluator {

    private static final int MAX_DAYS = 3_660_000;

    private final String source;
    private final Map<String, ExpressionType> types;
    private final Map<String, Object> values;
    private final Limits limits;
    private final Instant now;
    private final LongSupplier nanos;
    private final long deadline;
    private long steps;

    Evaluator(String source, Map<String, ExpressionType> types, Map<String, Object> values, Limits limits,
            Instant now, LongSupplier nanos) {
        this.source = source;
        this.types = types;
        this.values = values;
        this.limits = limits;
        this.now = now.truncatedTo(ChronoUnit.SECONDS);
        this.nanos = nanos;
        this.deadline = nanos.getAsLong() + limits.maxMillis() * 1_000_000L;
    }

    Instant now() {
        return now;
    }

    Limits limits() {
        return limits;
    }

    /** Takes {@code units} more steps: one for a part, more for work on a long text. */
    void charge(Node at, long units) {
        steps += units;
        if (steps > limits.maxSteps()) {
            throw fail(Kind.TOO_MANY_STEPS, at.offset(), "The evaluation needs more than " + limits.maxSteps()
                    + " steps, which is the most one calculation may take.");
        }
        if (nanos.getAsLong() > deadline) {
            throw fail(Kind.TOO_SLOW, at.offset(), "The evaluation takes longer than " + limits.maxMillis()
                    + " milliseconds, which is the most one calculation may take.");
        }
    }

    ExpressionException fail(Kind kind, int offset, String problem) {
        return new ExpressionException(kind, ExpressionException.Position.of(source, offset), problem);
    }

    /** The value of the part, or null for no value. */
    Object eval(Node node) {
        charge(node, 1);
        return switch (node) {
            case Node.Literal literal -> literal.value();
            case Node.FieldRef field -> field(field);
            case Node.Unary unary -> unary(unary);
            case Node.Binary binary -> binary(binary);
            case Node.Call call -> call(call);
        };
    }

    // ---- fields ----

    private Object field(Node.FieldRef field) {
        ExpressionType type = types.get(field.name());
        if (type == null) {
            throw fail(Kind.UNKNOWN_FIELD, field.offset(), "The field " + field.name() + " is not known to this "
                    + "calculation.");
        }
        try {
            return Values.external(type, values.get(field.name()), limits, field.name());
        } catch (ExpressionException invalid) {
            throw fail(invalid.kind(), field.offset(), invalid.problem());
        }
    }

    // ---- operators ----

    private Object unary(Node.Unary unary) {
        Object operand = eval(unary.operand());
        if (operand == null) {
            return null;
        }
        return switch (unary.op()) {
            case NEGATE -> ((BigDecimal) operand).negate();
            case NOT -> !((Boolean) operand);
        };
    }

    private Object binary(Node.Binary binary) {
        return switch (binary.op()) {
            case AND -> and(binary);
            case OR -> or(binary);
            case EQUAL, NOT_EQUAL -> equality(binary);
            case LESS, LESS_OR_EQUAL, GREATER, GREATER_OR_EQUAL -> ordering(binary);
            case ADD, SUBTRACT, MULTIPLY, DIVIDE, REMAINDER -> arithmetic(binary);
        };
    }

    /** Three-valued AND: false wins over no value, no value wins over true; the right side is skipped on false. */
    private Object and(Node.Binary binary) {
        Boolean left = (Boolean) eval(binary.left());
        if (Boolean.FALSE.equals(left)) {
            return Boolean.FALSE;
        }
        Boolean right = (Boolean) eval(binary.right());
        if (Boolean.FALSE.equals(right)) {
            return Boolean.FALSE;
        }
        return left == null || right == null ? null : Boolean.TRUE;
    }

    /** Three-valued OR: true wins over no value, no value wins over false; the right side is skipped on true. */
    private Object or(Node.Binary binary) {
        Boolean left = (Boolean) eval(binary.left());
        if (Boolean.TRUE.equals(left)) {
            return Boolean.TRUE;
        }
        Boolean right = (Boolean) eval(binary.right());
        if (Boolean.TRUE.equals(right)) {
            return Boolean.TRUE;
        }
        return left == null || right == null ? null : Boolean.FALSE;
    }

    /**
     * {@code x = NULL} and {@code x != NULL} (and the same with the empty text) ask whether there is a value; any other
     * comparison with no value gives no value.
     */
    private Object equality(Node.Binary binary) {
        boolean equal = binary.op() == BinaryOp.EQUAL;
        boolean leftBlank = isBlankLiteral(binary.left());
        boolean rightBlank = isBlankLiteral(binary.right());
        if (leftBlank || rightBlank) {
            if (leftBlank && rightBlank) {
                charge(binary, 1);
                return equal;
            }
            boolean blank = eval(leftBlank ? binary.right() : binary.left()) == null;
            return equal == blank;
        }
        Object left = eval(binary.left());
        Object right = eval(binary.right());
        if (left == null || right == null) {
            return null;
        }
        boolean same = left instanceof BigDecimal a ? a.compareTo((BigDecimal) right) == 0 : left.equals(right);
        return equal == same;
    }

    private static boolean isBlankLiteral(Node node) {
        return node instanceof Node.Literal literal && literal.value() == null;
    }

    private Object ordering(Node.Binary binary) {
        Object left = eval(binary.left());
        Object right = eval(binary.right());
        if (left == null || right == null) {
            return null;
        }
        int order = switch (left) {
            case BigDecimal a -> a.compareTo((BigDecimal) right);
            case String a -> a.compareTo((String) right);
            case LocalDate a -> a.compareTo((LocalDate) right);
            case Instant a -> a.compareTo((Instant) right);
            case LocalTime a -> a.compareTo((LocalTime) right);
            default -> throw fail(Kind.TYPE, binary.offset(), "These values cannot be put in order.");
        };
        return switch (binary.op()) {
            case LESS -> order < 0;
            case LESS_OR_EQUAL -> order <= 0;
            case GREATER -> order > 0;
            default -> order >= 0;
        };
    }

    private Object arithmetic(Node.Binary binary) {
        Object left = eval(binary.left());
        Object right = eval(binary.right());
        if (left == null || right == null) {
            return null;
        }
        return switch (binary.op()) {
            case ADD -> add(binary, left, right);
            case SUBTRACT -> subtract(binary, left, right);
            case MULTIPLY -> settle(binary, ((BigDecimal) left).multiply((BigDecimal) right));
            case DIVIDE -> {
                BigDecimal divisor = divisor(binary, right);
                yield settle(binary, ((BigDecimal) left).divide(divisor, Numbers.MAX_SCALE, RoundingMode.HALF_UP));
            }
            default -> settle(binary, ((BigDecimal) left).remainder(divisor(binary, right)));
        };
    }

    private Object add(Node.Binary binary, Object left, Object right) {
        if (left instanceof LocalDate date) {
            return plusDays(binary.offset(), date, (BigDecimal) right);
        }
        if (right instanceof LocalDate date) {
            return plusDays(binary.offset(), date, (BigDecimal) left);
        }
        return settle(binary, ((BigDecimal) left).add((BigDecimal) right));
    }

    private Object subtract(Node.Binary binary, Object left, Object right) {
        if (left instanceof LocalDate date) {
            if (right instanceof LocalDate other) {
                return BigDecimal.valueOf(ChronoUnit.DAYS.between(other, date));
            }
            return plusDays(binary.offset(), date, ((BigDecimal) right).negate());
        }
        return settle(binary, ((BigDecimal) left).subtract((BigDecimal) right));
    }

    private BigDecimal divisor(Node.Binary binary, Object right) {
        BigDecimal divisor = (BigDecimal) right;
        if (divisor.signum() == 0) {
            throw fail(Kind.DIVISION_BY_ZERO, binary.offset(), "This divides by zero. Test the divisor first, for "
                    + "example IF(b = 0, 0, a / b).");
        }
        return divisor;
    }

    private BigDecimal settle(Node at, BigDecimal result) {
        BigDecimal settled = Numbers.settle(result);
        if (settled == null) {
            throw fail(Kind.OVERFLOW, at.offset(), "The result is too large: a number has at most "
                    + Numbers.MAX_INTEGER_DIGITS + " digits before the point.");
        }
        return settled;
    }

    /** A date moved by a whole number of days, kept inside the years the language allows. */
    LocalDate plusDays(int offset, LocalDate date, BigDecimal days) {
        if (days.stripTrailingZeros().scale() > 0) {
            throw fail(Kind.OUT_OF_RANGE, offset, "A number of days must be a whole number.");
        }
        if (days.abs().compareTo(BigDecimal.valueOf(MAX_DAYS)) > 0) {
            throw fail(Kind.OUT_OF_RANGE, offset, "A date cannot be moved by more than " + MAX_DAYS + " days.");
        }
        LocalDate moved = date.plusDays(days.longValue());
        if (!Values.inRange(moved)) {
            throw fail(Kind.OUT_OF_RANGE, offset, "The result is a date outside the years 1 to 9999.");
        }
        return moved;
    }

    // ---- calls ----

    private Object call(Node.Call call) {
        Functions.Definition function = Functions.find(call.function()).orElseThrow(() -> fail(Kind.UNKNOWN_FUNCTION,
                call.offset(), "The function " + call.function() + " does not exist."));
        return function.implementation().apply(new Invocation(this, call));
    }

    /** The day of the moment, in UTC. */
    static LocalDate dayOf(Instant moment) {
        return moment.atOffset(ZoneOffset.UTC).toLocalDate();
    }
}
