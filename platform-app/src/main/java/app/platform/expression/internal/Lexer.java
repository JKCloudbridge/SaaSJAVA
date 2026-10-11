package app.platform.expression.internal;

import app.platform.expression.ExpressionException;
import app.platform.expression.ExpressionException.Kind;
import app.platform.expression.Limits;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Cuts the text into tokens (ADR-0076). It is deliberately strict, because what it refuses is what cannot be attacked:
 * outside a text literal only plain ASCII letters, digits, the operators and spaces exist, so look-alike letters,
 * zero-width characters and right-to-left marks cannot hide in a name or an operator; inside a text literal anything
 * printable is allowed, but never a control, format, private-use, unassigned or lone surrogate character. There are no
 * comments, no escapes other than a doubled quote, and no exponent form of a number. It reads the text once from left
 * to right, so its cost grows with the length of the text and nothing else.
 */
final class Lexer {

    /** The kinds of token. */
    enum Type {
        NUMBER, TEXT, NAME, PLUS, MINUS, STAR, SLASH, PERCENT, EQ, NE, LT, LE, GT, GE, LPAREN, RPAREN, COMMA, END
    }

    /**
     * One token.
     *
     * @param type its kind
     * @param text a name as written, or the unescaped content of a text literal, or the digits of a number
     * @param value the value of a number token
     * @param offset where it starts
     */
    record Token(Type type, String text, BigDecimal value, int offset) {
    }

    private static final int NAME_MAX = 80;

    private final String source;
    private final Limits limits;

    Lexer(String source, Limits limits) {
        this.source = source;
        this.limits = limits;
    }

    /** The tokens of the text, the last one being {@link Type#END}. */
    List<Token> tokens() {
        if (source.length() > limits.maxLength()) {
            throw new ExpressionException(Kind.TOO_LONG, null, "The expression is longer than " + limits.maxLength()
                    + " characters.");
        }
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int length = source.length();
        while (i < length) {
            char c = source.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else if (isLetter(c)) {
                i = name(tokens, i);
            } else if (isDigit(c)) {
                i = number(tokens, i);
            } else if (c == '\'') {
                i = text(tokens, i);
            } else {
                i = symbol(tokens, i, c);
            }
        }
        tokens.add(new Token(Type.END, "", null, length));
        return tokens;
    }

    private int name(List<Token> tokens, int start) {
        int i = start;
        while (i < source.length() && (isLetter(source.charAt(i)) || isDigit(source.charAt(i))
                || source.charAt(i) == '_')) {
            i++;
        }
        if (i - start > NAME_MAX) {
            throw fail(Kind.SYNTAX, start, "A name is at most " + NAME_MAX + " characters.");
        }
        tokens.add(new Token(Type.NAME, source.substring(start, i), null, start));
        return i;
    }

    private int number(List<Token> tokens, int start) {
        int i = start;
        while (i < source.length() && isDigit(source.charAt(i))) {
            i++;
        }
        if (i < source.length() && source.charAt(i) == '.') {
            i++;
            int fraction = i;
            while (i < source.length() && isDigit(source.charAt(i))) {
                i++;
            }
            if (i == fraction) {
                throw fail(Kind.SYNTAX, start, "A number needs digits after the point.");
            }
        }
        if (i < source.length() && (isLetter(source.charAt(i)) || source.charAt(i) == '_'
                || source.charAt(i) == '.')) {
            throw fail(Kind.SYNTAX, start, "A number must be followed by an operator, a bracket or a comma. Numbers "
                    + "are written in plain digits, with an optional point and no exponent.");
        }
        String digits = source.substring(start, i);
        BigDecimal value = Numbers.parse(digits, false);
        if (value == null) {
            throw fail(Kind.OVERFLOW, start, "A number has at most " + Numbers.MAX_INTEGER_DIGITS
                    + " digits before the point and " + Numbers.MAX_SCALE + " after it.");
        }
        tokens.add(new Token(Type.NUMBER, digits, value, start));
        return i;
    }

    private int text(List<Token> tokens, int start) {
        StringBuilder content = new StringBuilder();
        int i = start + 1;
        int length = source.length();
        while (true) {
            if (i >= length) {
                throw fail(Kind.SYNTAX, start, "This text has no closing quote.");
            }
            int cp = source.codePointAt(i);
            if (cp == '\'') {
                if (i + 1 < length && source.charAt(i + 1) == '\'') {
                    content.append('\'');
                    i += 2;
                    continue;
                }
                i++;
                break;
            }
            if (!allowedInText(cp)) {
                throw fail(Kind.SYNTAX, i, "This character cannot be used in a text (no control, invisible, "
                        + "direction or private-use characters).");
            }
            content.appendCodePoint(cp);
            i += Character.charCount(cp);
            if (content.length() > limits.maxTextLength()) {
                throw fail(Kind.TEXT_TOO_LONG, start, "This text is longer than " + limits.maxTextLength()
                        + " characters.");
            }
        }
        tokens.add(new Token(Type.TEXT, content.toString(), null, start));
        return i;
    }

    private int symbol(List<Token> tokens, int start, char c) {
        char next = start + 1 < source.length() ? source.charAt(start + 1) : '\0';
        switch (c) {
            case '+' -> tokens.add(new Token(Type.PLUS, "+", null, start));
            case '-' -> tokens.add(new Token(Type.MINUS, "-", null, start));
            case '*' -> tokens.add(new Token(Type.STAR, "*", null, start));
            case '/' -> tokens.add(new Token(Type.SLASH, "/", null, start));
            case '%' -> tokens.add(new Token(Type.PERCENT, "%", null, start));
            case '(' -> tokens.add(new Token(Type.LPAREN, "(", null, start));
            case ')' -> tokens.add(new Token(Type.RPAREN, ")", null, start));
            case ',' -> tokens.add(new Token(Type.COMMA, ",", null, start));
            case '=' -> {
                if (next == '=') {
                    throw fail(Kind.SYNTAX, start, "Compare with a single = (there is no ==).");
                }
                tokens.add(new Token(Type.EQ, "=", null, start));
            }
            case '!' -> {
                if (next != '=') {
                    throw fail(Kind.SYNTAX, start, "Write NOT for a negation and != for \"is not equal to\".");
                }
                tokens.add(new Token(Type.NE, "!=", null, start));
                return start + 2;
            }
            case '<' -> {
                if (next == '>') {
                    throw fail(Kind.SYNTAX, start, "Write != for \"is not equal to\".");
                }
                if (next == '=') {
                    tokens.add(new Token(Type.LE, "<=", null, start));
                    return start + 2;
                }
                tokens.add(new Token(Type.LT, "<", null, start));
            }
            case '>' -> {
                if (next == '=') {
                    tokens.add(new Token(Type.GE, ">=", null, start));
                    return start + 2;
                }
                tokens.add(new Token(Type.GT, ">", null, start));
            }
            default -> throw fail(Kind.SYNTAX, start, unavailable(c));
        }
        return start + 1;
    }

    /** Why a character cannot be used, in words that help the person who typed it. */
    private static String unavailable(char c) {
        return switch (c) {
            case '"' -> "Text is written between single quotes, like 'this'.";
            case '.' -> "A dot is not allowed: an expression has no method calls and no fields of other objects.";
            case '&', '|' -> "Write AND and OR instead of symbols.";
            case '^' -> "There is no power operator.";
            case '$', '#', '{', '}', '@' -> "This character starts constructs of other languages that do not exist "
                    + "here.";
            default -> "This character is not allowed in an expression.";
        };
    }

    private ExpressionException fail(Kind kind, int offset, String problem) {
        return new ExpressionException(kind, ExpressionException.Position.of(source, offset), problem);
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** Whether a text literal may hold the character: anything that can be seen or is an ordinary space. */
    static boolean allowedInText(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
                    Character.UNASSIGNED, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> false;
            default -> true;
        };
    }
}
