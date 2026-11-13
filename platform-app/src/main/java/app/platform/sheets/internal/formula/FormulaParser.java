package app.platform.sheets.internal.formula;

import app.platform.sheets.internal.formula.Node.Binary;
import app.platform.sheets.internal.formula.Node.Bool;
import app.platform.sheets.internal.formula.Node.Call;
import app.platform.sheets.internal.formula.Node.Group;
import app.platform.sheets.internal.formula.Node.Negate;
import app.platform.sheets.internal.formula.Node.Num;
import app.platform.sheets.internal.formula.Node.Percent;
import app.platform.sheets.internal.formula.Node.Range;
import app.platform.sheets.internal.formula.Node.Ref;
import app.platform.sheets.internal.formula.Node.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the stored form of a formula (the spreadsheet text in which every reference is a token that names a column
 * and a row by their permanent identifiers, ADR-0252) into a {@link Node} tree. This is the <em>gate</em>, in the
 * order the decision fixes: the length and the nesting of brackets are checked on the text <strong>before anything
 * parses it</strong>; then the text is split into parts, and the parser admits only the closed set of parts (numbers,
 * text, TRUE and FALSE, references and ranges, the arithmetic and comparison operators, percent, signs, brackets and
 * the calls of the allowed functions). Anything else is refused with a stable reason that never repeats the formula.
 *
 * <p>It is a small parser of its own for this closed grammar, not a library's: the stored form already holds the
 * references as tokens, the interpreter needs this tree anyway, and a library's quirks (it files some functions as a
 * name plus a call) never enter the gate. Nothing here runs a formula.
 */
public final class FormulaParser {

    private static final int MOST_DIGITS = 20;
    private static final int MOST_PLAIN = 30;

    private final FormulaLimits limits;
    private final List<Tok> tokens = new ArrayList<>();
    private int at;
    private int depth;
    private int nodes;

    private FormulaParser(FormulaLimits limits) {
        this.limits = limits;
    }

    private enum Kind { NUMBER, TEXT, REF, WORD, OPERATOR }

    private record Tok(Kind kind, String text, Ref ref) {
    }

    /**
     * Parses a formula in its stored form (without a leading equals sign).
     *
     * @param stored the stored form
     * @param limits the bounds
     * @return the tree
     * @throws FormulaRefused with the reason, when the formula is not accepted
     */
    public static Node parse(String stored, FormulaLimits limits) {
        if (stored.isBlank()) {
            throw new FormulaRefused("empty_formula");
        }
        if (stored.length() > limits.maxChars()) {
            throw new FormulaRefused("too_long");
        }
        bracketDepth(stored, limits.maxDepth());
        FormulaParser parser = new FormulaParser(limits);
        parser.tokenize(stored);
        Node tree = parser.comparison();
        if (parser.at < parser.tokens.size()) {
            throw new FormulaRefused("unexpected_token");
        }
        return tree;
    }

    /** The deepest nesting of round brackets outside text constants; refused above the bound, before any parsing. */
    private static void bracketDepth(String text, int most) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                if (++depth > most) {
                    throw new FormulaRefused("too_deep");
                }
            } else if (!quoted && c == ')' && depth > 0) {
                depth--;
            }
        }
    }

    // ---- the parts ----

    private void tokenize(String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                i++;
            } else if (c == '"') {
                StringBuilder value = new StringBuilder();
                int k = i + 1;
                boolean closed = false;
                while (k < s.length()) {
                    if (s.charAt(k) == '"') {
                        if (k + 1 < s.length() && s.charAt(k + 1) == '"') {
                            value.append('"');
                            k += 2;
                            continue;
                        }
                        closed = true;
                        k++;
                        break;
                    }
                    value.append(s.charAt(k++));
                }
                if (!closed) {
                    throw new FormulaRefused("unclosed_text");
                }
                tokens.add(new Tok(Kind.TEXT, value.toString(), null));
                i = k;
            } else if (c == '@') {
                int close = s.indexOf('}', i);
                if (!s.startsWith("@{", i) || close < 0) {
                    throw new FormulaRefused("bad_reference_token");
                }
                tokens.add(new Tok(Kind.REF, null, reference(s.substring(i + 2, close))));
                i = close + 1;
            } else if (digit(c) || c == '.' && i + 1 < s.length() && digit(s.charAt(i + 1))) {
                int k = i;
                while (k < s.length() && digit(s.charAt(k))) {
                    k++;
                }
                if (k < s.length() && s.charAt(k) == '.') {
                    k++;
                    while (k < s.length() && digit(s.charAt(k))) {
                        k++;
                    }
                }
                if (k < s.length() && (s.charAt(k) == 'e' || s.charAt(k) == 'E')) {
                    int m = k + 1;
                    if (m < s.length() && (s.charAt(m) == '+' || s.charAt(m) == '-')) {
                        m++;
                    }
                    if (m < s.length() && digit(s.charAt(m))) {
                        while (m < s.length() && digit(s.charAt(m))) {
                            m++;
                        }
                        k = m;
                    }
                }
                tokens.add(new Tok(Kind.NUMBER, s.substring(i, k), null));
                i = k;
            } else if (letter(c)) {
                int k = i;
                while (k < s.length() && (letter(s.charAt(k)) || digit(s.charAt(k)) || s.charAt(k) == '_'
                        || s.charAt(k) == '.')) {
                    k++;
                }
                tokens.add(new Tok(Kind.WORD, s.substring(i, k).toUpperCase(Locale.ROOT), null));
                i = k;
            } else {
                String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
                if (two.equals("<=") || two.equals(">=") || two.equals("<>")) {
                    tokens.add(new Tok(Kind.OPERATOR, two, null));
                    i += 2;
                } else if ("+-*/^%=<>(),:".indexOf(c) >= 0) {
                    tokens.add(new Tok(Kind.OPERATOR, String.valueOf(c), null));
                    i++;
                } else {
                    throw new FormulaRefused("character_not_allowed");
                }
            }
        }
        if (tokens.size() > limits.maxNodes() * 3) {
            throw new FormulaRefused("too_complex");
        }
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean letter(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    private static boolean idChar(char c) {
        return digit(c) || letter(c) || c == '_' || c == '-';
    }

    private static boolean identifier(String s) {
        if (s.isEmpty() || s.length() > 40) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!idChar(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** The inside of a reference token: COL, COL,ROW, COL,ROW,cr, COL,,c or COL,*. */
    static Ref reference(String inside) {
        String[] parts = inside.split(",", -1);
        if (parts.length > 3 || !identifier(parts[0])) {
            throw new FormulaRefused("bad_reference_token");
        }
        String column = parts[0];
        String row = parts.length > 1 ? parts[1] : "";
        String flags = parts.length > 2 ? parts[2] : "";
        if (parts.length == 2 && row.isEmpty()) {
            throw new FormulaRefused("bad_reference_token");
        }
        if (row.equals("*")) {
            if (parts.length > 2) {
                throw new FormulaRefused("bad_reference_token");
            }
            return new Ref(column, null, false, false, true);
        }
        if (!row.isEmpty() && !identifier(row)) {
            throw new FormulaRefused("bad_reference_token");
        }
        if (parts.length > 2 && !(flags.equals("c") || flags.equals("r") || flags.equals("cr"))) {
            throw new FormulaRefused("bad_reference_token");
        }
        boolean absoluteColumn = flags.contains("c");
        boolean absoluteRow = flags.contains("r");
        if (row.isEmpty() && absoluteRow) {
            throw new FormulaRefused("bad_reference_token");
        }
        return new Ref(column, row.isEmpty() ? null : row, absoluteColumn, absoluteRow, false);
    }

    // ---- the grammar ----

    private Tok peek() {
        return at < tokens.size() ? tokens.get(at) : null;
    }

    private boolean isOperator(String text) {
        Tok t = peek();
        return t != null && t.kind() == Kind.OPERATOR && t.text().equals(text);
    }

    private void enter() {
        if (++depth > limits.maxDepth()) {
            throw new FormulaRefused("too_deep");
        }
    }

    private <T extends Node> T made(T node) {
        if (++nodes > limits.maxNodes()) {
            throw new FormulaRefused("too_complex");
        }
        return node;
    }

    private Node comparison() {
        Node left = additive();
        while (peek() != null && peek().kind() == Kind.OPERATOR && List.of("=", "<>", "<", "<=", ">", ">=")
                .contains(peek().text())) {
            String operator = tokens.get(at++).text();
            left = made(new Binary(operator, left, additive()));
        }
        return left;
    }

    private Node additive() {
        Node left = multiplicative();
        while (isOperator("+") || isOperator("-")) {
            String operator = tokens.get(at++).text();
            left = made(new Binary(operator, left, multiplicative()));
        }
        return left;
    }

    private Node multiplicative() {
        Node left = power();
        while (isOperator("*") || isOperator("/")) {
            String operator = tokens.get(at++).text();
            left = made(new Binary(operator, left, power()));
        }
        return left;
    }

    private Node power() {
        Node left = unary();
        while (isOperator("^")) {
            at++;
            left = made(new Binary("^", left, unary()));
        }
        return left;
    }

    private Node unary() {
        if (isOperator("-")) {
            at++;
            enter();
            try {
                return made(new Negate(unary()));
            } finally {
                depth--;
            }
        }
        if (isOperator("+")) {
            // A plus sign changes nothing: it has no place in the one form of a formula.
            at++;
            enter();
            try {
                return unary();
            } finally {
                depth--;
            }
        }
        return postfix();
    }

    private Node postfix() {
        Node operand = primary();
        while (isOperator("%")) {
            at++;
            operand = made(new Percent(operand));
        }
        return operand;
    }

    private Node primary() {
        Tok t = peek();
        if (t == null) {
            throw new FormulaRefused("unexpected_end");
        }
        at++;
        switch (t.kind()) {
            case NUMBER:
                return made(number(t.text()));
            case TEXT:
                return made(new Text(t.text()));
            case REF:
                return reference(t.ref());
            case WORD:
                return word(t.text());
            default:
                break;
        }
        if (t.text().equals("(")) {
            enter();
            Node inner = comparison();
            depth--;
            if (!isOperator(")")) {
                throw new FormulaRefused("missing_bracket");
            }
            at++;
            return made(new Group(inner));
        }
        throw new FormulaRefused("unexpected_token");
    }

    private Node reference(Ref first) {
        if (isOperator(":")) {
            at++;
            Tok second = peek();
            if (second == null || second.kind() != Kind.REF) {
                throw new FormulaRefused("bad_range");
            }
            at++;
            Ref to = second.ref();
            if ((first.growing() || to.growing()) && !first.column().equals(to.column())) {
                throw new FormulaRefused("bad_range");
            }
            return made(new Range(first, to));
        }
        return made(first);
    }

    private Node word(String name) {
        if (name.equals("TRUE") || name.equals("FALSE")) {
            return made(new Bool(name.equals("TRUE")));
        }
        if (!isOperator("(")) {
            throw new FormulaRefused("name_not_allowed");
        }
        FormulaFunctions.Arity arity = FormulaFunctions.of(name);
        if (arity == null) {
            throw new FormulaRefused("function_not_allowed");
        }
        at++;
        enter();
        List<Node> arguments = new ArrayList<>();
        if (!isOperator(")")) {
            while (true) {
                if (isOperator(",") || isOperator(")")) {
                    throw new FormulaRefused("empty_argument");
                }
                arguments.add(comparison());
                if (isOperator(",")) {
                    at++;
                    continue;
                }
                break;
            }
        }
        depth--;
        if (!isOperator(")")) {
            throw new FormulaRefused("missing_bracket");
        }
        at++;
        if (arguments.size() < arity.least() || arguments.size() > arity.most()) {
            throw new FormulaRefused("wrong_argument_count");
        }
        if (name.equals("COUNT")) {
            for (Node argument : arguments) {
                Node bare = argument;
                while (bare instanceof Group g) {
                    bare = g.inner();
                }
                if (!(bare instanceof Ref) && !(bare instanceof Range)) {
                    throw new FormulaRefused("count_needs_cells");
                }
            }
        }
        if (name.equals("TEXT") && !(arguments.get(1) instanceof Text format
                && FormulaFunctions.TEXT_FORMATS.contains(format.value()))) {
            throw new FormulaRefused("text_format_not_allowed");
        }
        return made(new Call(name, arguments));
    }

    // ---- numbers ----

    private static Num number(String text) {
        String canonical = canonicalNumber(text);
        return new Num(Double.parseDouble(canonical), canonical);
    }

    /**
     * The one spelling of a number: plain digits, no leading zero, no trailing zero after the point, no exponent.
     * {@code 1.0}, {@code 01}, {@code .5} and {@code 5E-1} all have one. A number with more than twenty significant
     * digits, or that would need more than thirty places, is refused.
     *
     * @param text the number as typed
     * @return its canonical spelling
     */
    public static String canonicalNumber(String text) {
        String mantissa = text;
        long exponent = 0;
        int e = Math.max(text.indexOf('e'), text.indexOf('E'));
        if (e >= 0) {
            mantissa = text.substring(0, e);
            String digits = text.substring(e + 1);
            String unsigned = digits.startsWith("+") || digits.startsWith("-") ? digits.substring(1) : digits;
            if (unsigned.length() > 4) {
                throw new FormulaRefused("number_range");
            }
            exponent = Long.parseLong(digits.startsWith("+") ? digits.substring(1) : digits);
        }
        int dot = mantissa.indexOf('.');
        String whole = dot < 0 ? mantissa : mantissa.substring(0, dot);
        String fraction = dot < 0 ? "" : mantissa.substring(dot + 1);
        String digits = whole + fraction;
        long scale = exponent - fraction.length();
        int start = 0;
        while (start < digits.length() && digits.charAt(start) == '0') {
            start++;
        }
        digits = digits.substring(start);
        if (digits.isEmpty()) {
            return "0";
        }
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') {
            end--;
            scale++;
        }
        digits = digits.substring(0, end);
        if (digits.length() > MOST_DIGITS) {
            throw new FormulaRefused("number_range");
        }
        if (scale >= 0) {
            if (digits.length() + scale > MOST_PLAIN) {
                throw new FormulaRefused("number_range");
            }
            return digits + "0".repeat((int) scale);
        }
        long places = -scale;
        if (places > MOST_PLAIN) {
            throw new FormulaRefused("number_range");
        }
        if (digits.length() > places) {
            int split = (int) (digits.length() - places);
            return digits.substring(0, split) + "." + digits.substring(split);
        }
        return "0." + "0".repeat((int) (places - digits.length())) + digits;
    }
}
