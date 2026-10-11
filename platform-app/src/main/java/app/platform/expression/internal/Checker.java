package app.platform.expression.internal;

import app.platform.expression.ExpressionException;
import app.platform.expression.ExpressionException.Kind;
import app.platform.expression.ExpressionType;
import app.platform.expression.Purpose;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The type checker (ADR-0076): walks the tree once against the fields the caller names (a map of field name to type,
 * nothing from a database) and says what type the expression gives, which fields it uses and whether it reads the
 * clock, or refuses it with the place and the kind of problem. Whatever passes the checker can be evaluated without any
 * type surprise: an operator never meets a value of a type it was not checked for.
 *
 * <p>The word NULL has the type {@code NONE}, which fits where any type is expected; that is how {@code x = NULL} and
 * {@code IF(a, 1, NULL)} are accepted.
 */
final class Checker {

    /**
     * What the check found.
     *
     * @param type the type the expression gives
     * @param fields the fields it uses, in alphabetical order
     * @param usesClock whether it calls {@code TODAY} or {@code NOW}
     */
    record Result(ExpressionType type, List<String> fields, boolean usesClock) {

        /** Copies the list. */
        Result {
            fields = List.copyOf(fields);
        }
    }

    private final String source;
    private final Map<String, ExpressionType> schema;
    private final Purpose purpose;
    private final Set<String> fields = new TreeSet<>();
    private boolean usesClock;

    Checker(String source, Map<String, ExpressionType> schema, Purpose purpose) {
        this.source = source;
        this.schema = schema;
        this.purpose = purpose;
    }

    /** Checks the whole tree. */
    Result check(Node root) {
        ExpressionType type = type(root);
        if (purpose == Purpose.VALIDATION_RULE && type != ExpressionType.BOOLEAN) {
            throw fail(Kind.TYPE, root.offset(), "A validation rule must give true or false (it rejects the save when "
                    + "it is true), but this gives " + type.words() + ".");
        }
        return new Result(type, new ArrayList<>(fields), usesClock);
    }

    ExpressionException fail(Kind kind, int offset, String problem) {
        return new ExpressionException(kind, ExpressionException.Position.of(source, offset), problem);
    }

    ExpressionType type(Node node) {
        return switch (node) {
            case Node.Literal literal -> literal.type();
            case Node.FieldRef field -> field(field);
            case Node.Unary unary -> unary(unary);
            case Node.Binary binary -> binary(binary);
            case Node.Call call -> call(call);
        };
    }

    private ExpressionType field(Node.FieldRef field) {
        ExpressionType type = schema.get(field.name());
        if (type == null) {
            if (Functions.find(field.name().toUpperCase(Locale.ROOT)).isPresent()) {
                throw fail(Kind.UNKNOWN_FIELD, field.offset(), field.name() + " is a function: write its brackets and "
                        + "arguments, for example " + Functions.find(field.name().toUpperCase(Locale.ROOT))
                        .orElseThrow().signature() + ".");
            }
            throw fail(Kind.UNKNOWN_FIELD, field.offset(), "The field " + field.name() + " does not exist on this "
                    + "object. Use the API name of a field of the same object.");
        }
        fields.add(field.name());
        return type;
    }

    private ExpressionType unary(Node.Unary unary) {
        ExpressionType operand = type(unary.operand());
        ExpressionType wanted = unary.op() == Node.UnaryOp.NEGATE ? ExpressionType.NUMBER : ExpressionType.BOOLEAN;
        if (operand != wanted && operand != ExpressionType.NONE) {
            throw fail(Kind.TYPE, unary.offset(), (unary.op() == Node.UnaryOp.NEGATE ? "A minus sign" : "NOT")
                    + " needs " + wanted.words() + ", not " + operand.words() + ".");
        }
        return wanted;
    }

    private ExpressionType binary(Node.Binary binary) {
        ExpressionType left = type(binary.left());
        ExpressionType right = type(binary.right());
        return switch (binary.op()) {
            case AND, OR -> logical(binary, left, right);
            case EQUAL, NOT_EQUAL -> equality(binary, left, right);
            case LESS, LESS_OR_EQUAL, GREATER, GREATER_OR_EQUAL -> ordering(binary, left, right);
            case ADD -> add(binary, left, right);
            case SUBTRACT -> subtract(binary, left, right);
            case MULTIPLY, DIVIDE, REMAINDER -> numeric(binary, left, right);
        };
    }

    private ExpressionType logical(Node.Binary binary, ExpressionType left, ExpressionType right) {
        for (ExpressionType side : List.of(left, right)) {
            if (side != ExpressionType.BOOLEAN && side != ExpressionType.NONE) {
                throw fail(Kind.TYPE, binary.offset(), binary.op().symbol() + " joins two conditions (true or false), "
                        + "but one side is " + side.words() + ".");
            }
        }
        return ExpressionType.BOOLEAN;
    }

    private ExpressionType equality(Node.Binary binary, ExpressionType left, ExpressionType right) {
        if (left != ExpressionType.NONE && right != ExpressionType.NONE && left != right) {
            throw fail(Kind.TYPE, binary.offset(), "Cannot compare " + left.words() + " with " + right.words()
                    + ": both sides must be of the same type.");
        }
        return ExpressionType.BOOLEAN;
    }

    private ExpressionType ordering(Node.Binary binary, ExpressionType left, ExpressionType right) {
        ExpressionType shared = left == ExpressionType.NONE ? right : left;
        if (left != ExpressionType.NONE && right != ExpressionType.NONE && left != right) {
            throw fail(Kind.TYPE, binary.offset(), "Cannot compare " + left.words() + " with " + right.words()
                    + ": both sides must be of the same type.");
        }
        if (shared == ExpressionType.BOOLEAN || shared == ExpressionType.NONE) {
            throw fail(Kind.TYPE, binary.offset(), "Only numbers, text, dates and times can be put in order with "
                    + binary.op().symbol() + ", not " + shared.words() + ".");
        }
        return ExpressionType.BOOLEAN;
    }

    private ExpressionType add(Node.Binary binary, ExpressionType left, ExpressionType right) {
        if (isNumber(left) && isNumber(right)) {
            return ExpressionType.NUMBER;
        }
        if ((left == ExpressionType.DATE && isNumber(right)) || (isNumber(left) && right == ExpressionType.DATE)) {
            return ExpressionType.DATE;
        }
        throw fail(Kind.TYPE, binary.offset(), "A plus sign adds numbers, or adds a number of days to a date; it "
                + "cannot add " + left.words() + " and " + right.words() + ". Join texts with CONCAT.");
    }

    private ExpressionType subtract(Node.Binary binary, ExpressionType left, ExpressionType right) {
        if (isNumber(left) && isNumber(right)) {
            return ExpressionType.NUMBER;
        }
        if (left == ExpressionType.DATE && isNumber(right)) {
            return ExpressionType.DATE;
        }
        if (left == ExpressionType.DATE && right == ExpressionType.DATE) {
            return ExpressionType.NUMBER;
        }
        throw fail(Kind.TYPE, binary.offset(), "A minus sign subtracts numbers, takes days from a date, or gives the "
                + "days between two dates; it cannot subtract " + right.words() + " from " + left.words() + ".");
    }

    private ExpressionType numeric(Node.Binary binary, ExpressionType left, ExpressionType right) {
        if (!isNumber(left) || !isNumber(right)) {
            throw fail(Kind.TYPE, binary.offset(), binary.op().symbol() + " needs two numbers, not " + left.words()
                    + " and " + right.words() + ".");
        }
        return ExpressionType.NUMBER;
    }

    private static boolean isNumber(ExpressionType type) {
        return type == ExpressionType.NUMBER || type == ExpressionType.NONE;
    }

    private ExpressionType call(Node.Call call) {
        Functions.Definition function = Functions.find(call.function()).orElseThrow(() -> fail(
                Kind.UNKNOWN_FUNCTION, call.offset(), "The function " + call.function() + " does not exist. The "
                        + "functions are fixed; the guide lists them."));
        int count = call.arguments().size();
        if (count < function.min() || count > function.max()) {
            throw fail(Kind.ARGUMENTS, call.offset(), function.name() + " takes " + arity(function) + " but is given "
                    + count + ". Write it as " + function.signature() + ".");
        }
        if (function.clock()) {
            refuseClock(call, function);
            usesClock = true;
        }
        List<ExpressionType> types = new ArrayList<>();
        for (Node argument : call.arguments()) {
            types.add(type(argument));
        }
        return function.typing().type(new TypeArgs(this, call, Collections.unmodifiableList(types)));
    }

    private void refuseClock(Node.Call call, Functions.Definition function) {
        if (!purpose.clockAllowed()) {
            throw fail(Kind.NOT_ALLOWED, call.offset(), function.name() + " is not allowed in a formula, because a "
                    + "stored value would silently go stale. Use it in a validation rule, which is judged when the "
                    + "record is saved.");
        }
    }

    /**
     * Refuses what the purpose does not allow without needing to know the fields: today that is the clock in a
     * formula. It lets a formula be refused the moment it is saved, before the other fields are looked at.
     */
    void checkUse(Node node) {
        if (node instanceof Node.Unary unary) {
            checkUse(unary.operand());
        } else if (node instanceof Node.Binary binary) {
            checkUse(binary.left());
            checkUse(binary.right());
        } else if (node instanceof Node.Call call) {
            Functions.find(call.function()).filter(Functions.Definition::clock)
                    .ifPresent(function -> refuseClock(call, function));
            call.arguments().forEach(this::checkUse);
        }
    }

    private static String arity(Functions.Definition function) {
        if (function.min() == function.max()) {
            return function.min() == 0 ? "no arguments" : function.min() + (function.min() == 1 ? " argument"
                    : " arguments");
        }
        return "between " + function.min() + " and " + function.max() + " arguments";
    }
}
