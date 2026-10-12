package app.platform.expression.internal;

import app.platform.expression.ExpressionException;
import app.platform.expression.ExpressionException.Kind;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * What a function sees while it runs: its arguments, which it evaluates one at a time and only when it needs them (so
 * a function can skip an argument, as {@code IF} does), a way to charge steps for work on a long text, and a way to
 * fail with the place of the call or of one argument. A function never sees the tree, the values or the clock directly.
 */
final class Invocation {

    private final Evaluator evaluator;
    private final Node.Call call;

    Invocation(Evaluator evaluator, Node.Call call) {
        this.evaluator = evaluator;
        this.call = call;
    }

    /** How many arguments the call has (the checker has verified the number). */
    int count() {
        return call.arguments().size();
    }

    /** The value of the argument, or null for no value; evaluating it takes its steps. */
    Object value(int index) {
        return evaluator.eval(call.arguments().get(index));
    }

    BigDecimal number(int index) {
        return (BigDecimal) value(index);
    }

    String text(int index) {
        return (String) value(index);
    }

    /**
     * The argument as a whole number between the bounds, or null for no value.
     *
     * @param what what the number is, for the message ("the number of characters")
     */
    Integer whole(int index, int min, int max, String what) {
        BigDecimal number = number(index);
        if (number == null) {
            return null;
        }
        if (number.stripTrailingZeros().scale() > 0) {
            throw failAt(index, Kind.OUT_OF_RANGE, "Argument " + (index + 1) + " of " + call.function() + " is "
                    + what + " and must be a whole number.");
        }
        if (number.compareTo(BigDecimal.valueOf(min)) < 0 || number.compareTo(BigDecimal.valueOf(max)) > 0) {
            throw failAt(index, Kind.OUT_OF_RANGE, "Argument " + (index + 1) + " of " + call.function() + " is "
                    + what + " and must be between " + min + " and " + max + ".");
        }
        return number.intValueExact();
    }

    /** Takes steps for work that grows with the size of a text: one per hundred characters. */
    void chargeText(int characters) {
        evaluator.charge(call, characters / 100L);
    }

    /** The longest text a result may be. */
    int maxText() {
        return evaluator.limits().maxTextLength();
    }

    /** The moment the evaluation was given. */
    Instant now() {
        return evaluator.now();
    }

    Evaluator evaluator() {
        return evaluator;
    }

    /** A failure at the call. */
    ExpressionException fail(Kind kind, String problem) {
        return evaluator.fail(kind, call.offset(), problem);
    }

    /** A failure at one argument. */
    ExpressionException failAt(int index, Kind kind, String problem) {
        return evaluator.fail(kind, call.arguments().get(index).offset(), problem);
    }

    /** The text, or null when it is empty, after checking it against the longest allowed result. */
    String result(String text) {
        if (text != null && text.length() > maxText()) {
            throw fail(Kind.TEXT_TOO_LONG, "The text this makes is longer than " + maxText() + " characters.");
        }
        return Values.text(text);
    }
}
