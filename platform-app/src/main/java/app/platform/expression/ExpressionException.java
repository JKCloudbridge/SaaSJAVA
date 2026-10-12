package app.platform.expression;

import java.io.Serializable;

/**
 * The one kind of failure this module reports: the text is not a valid expression, does not fit the fields it is
 * checked against, goes over a bound, or cannot be evaluated for the values it was given (a division by zero, a number
 * that is too large). Nothing else ever escapes the engine.
 *
 * <p>The message is plain words about the kind of problem and the place. It never repeats what was typed, except the
 * name of a field or a function (names have a fixed, safe shape). The text of an expression belongs to the
 * organization: the answer of the API may show the message to the person who typed it, but it is never logged and
 * never written to the audit trail. The exception carries no stack trace: it is an ordinary answer, and a flood of
 * them (a fuzz run, an attack) must stay cheap.
 */
public final class ExpressionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** What went wrong. */
    public enum Kind {
        /** The text does not follow the grammar, or uses a character that is not allowed. */
        SYNTAX(false),
        /** The text is longer than the limit. */
        TOO_LONG(true),
        /** The tree has more parts than the limit. */
        TOO_MANY_NODES(true),
        /** Brackets, calls or prefix operators nest deeper than the limit. */
        TOO_DEEP(true),
        /** The construct exists but is not allowed here (the clock in a formula). */
        NOT_ALLOWED(false),
        /** A field the schema does not have. */
        UNKNOWN_FIELD(false),
        /** A function the library does not have. */
        UNKNOWN_FUNCTION(false),
        /** A call with too few or too many arguments. */
        ARGUMENTS(false),
        /** Values of types that do not fit the operator or function, or a result of the wrong type. */
        TYPE(false),
        /** A division or remainder by zero. */
        DIVISION_BY_ZERO(false),
        /** A number that is too large or has too many digits. */
        OVERFLOW(false),
        /** A text that is longer than the limit. */
        TEXT_TOO_LONG(true),
        /** The evaluation took more steps than the limit. */
        TOO_MANY_STEPS(true),
        /** The evaluation took longer than the limit. */
        TOO_SLOW(true),
        /** A value given for a field that does not fit the field's type. */
        INVALID_VALUE(false),
        /** A text that is not a number, given to {@code VALUE}. */
        NOT_A_NUMBER(false),
        /** A date or an argument outside what is allowed. */
        OUT_OF_RANGE(false);

        private final boolean limit;

        Kind(boolean limit) {
            this.limit = limit;
        }

        /** Whether the problem is that a bound was reached. */
        public boolean isLimit() {
            return limit;
        }
    }

    /**
     * Where in the text a problem is. The first character is line 1, column 1.
     *
     * @param offset the index of the character, counting from 0
     * @param line the line, counting from 1
     * @param column the column on the line, counting from 1
     */
    public record Position(int offset, int line, int column) implements Serializable {

        /** The position of the character at {@code offset} of {@code text} (past the end counts as the end). */
        public static Position of(String text, int offset) {
            int at = Math.max(0, Math.min(offset, text.length()));
            int line = 1;
            int column = 1;
            for (int i = 0; i < at; i++) {
                char c = text.charAt(i);
                if (c == '\n') {
                    line++;
                    column = 1;
                } else if (c != '\r') {
                    column++;
                }
            }
            return new Position(at, line, column);
        }
    }

    private final Kind kind;
    private final Position position;
    private final String problem;

    /**
     * An error with a place.
     *
     * @param kind what went wrong
     * @param position where, or null when the problem is about the whole expression
     * @param problem the words, without the place
     */
    public ExpressionException(Kind kind, Position position, String problem) {
        super(position == null ? problem : "Line " + position.line() + ", column " + position.column() + ": " + problem,
                null, false, false);
        this.kind = kind;
        this.position = position;
        this.problem = problem;
    }

    /** What went wrong. */
    public Kind kind() {
        return kind;
    }

    /** Where, or null when the problem is about the whole expression. */
    public Position position() {
        return position;
    }

    /** The words, without the place. */
    public String problem() {
        return problem;
    }
}
