package app.platform.expression.internal;

import app.platform.expression.ExpressionException;
import app.platform.expression.ExpressionException.Kind;
import app.platform.expression.ExpressionType;
import java.util.ArrayList;
import java.util.List;

/**
 * What the typing rule of a function sees while the checker works: the types of the arguments and ways to say what is
 * wrong, with the place of the argument. A rule asks {@link #expect} and {@link #unify}; the first problem found is
 * the one reported.
 */
final class TypeArgs {

    private final Checker checker;
    private final Node.Call call;
    private final List<ExpressionType> types;

    TypeArgs(Checker checker, Node.Call call, List<ExpressionType> types) {
        this.checker = checker;
        this.call = call;
        this.types = List.copyOf(types);
    }

    int count() {
        return types.size();
    }

    ExpressionType type(int index) {
        return types.get(index);
    }

    /** Requires the argument to be one of the types (or the word NULL, which fits anywhere). */
    void expect(int index, ExpressionType... allowed) {
        ExpressionType actual = types.get(index);
        if (actual == ExpressionType.NONE) {
            return;
        }
        for (ExpressionType type : allowed) {
            if (actual == type) {
                return;
            }
        }
        List<String> wanted = new ArrayList<>();
        for (ExpressionType type : allowed) {
            wanted.add(type.words());
        }
        throw problem(index, Kind.TYPE, "Argument " + (index + 1) + " of " + call.function() + " must be "
                + String.join(" or ", wanted) + ", not " + actual.words() + ".");
    }

    /** The one type the arguments from {@code from} to the end share; the word NULL fits any of them. */
    ExpressionType unify(int from) {
        ExpressionType shared = ExpressionType.NONE;
        for (int i = from; i < types.size(); i++) {
            ExpressionType type = types.get(i);
            if (type == ExpressionType.NONE) {
                continue;
            }
            if (shared == ExpressionType.NONE) {
                shared = type;
            } else if (shared != type) {
                throw problem(i, Kind.TYPE, "Argument " + (i + 1) + " of " + call.function() + " is " + type.words()
                        + " but the values before it are " + shared.words() + ": they must be of the same type.");
            }
        }
        return shared;
    }

    /** A problem with one argument. */
    ExpressionException problem(int index, Kind kind, String text) {
        return checker.fail(kind, call.arguments().get(index).offset(), text);
    }

    /** A problem with the call as a whole. */
    ExpressionException problem(Kind kind, String text) {
        return checker.fail(kind, call.offset(), text);
    }
}
