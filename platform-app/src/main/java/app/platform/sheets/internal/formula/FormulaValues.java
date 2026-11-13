package app.platform.sheets.internal.formula;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * How values are converted, compared, rounded and shown (SH2). A value is {@code null} (an empty cell), a
 * {@link Double}, a {@link String}, a {@link Boolean} or an {@link XlError}. The rules follow the documented behaviour
 * of the spreadsheet functions, and the browser's copy of this class follows the same rules (one shared case file
 * holds both to them). There is no locale: a dot is the decimal point and the nine formats of {@code TEXT} are fixed.
 */
public final class FormulaValues {

    private static final Pattern PLAIN_NUMBER = Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)(e[+-]?\\d+)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PERCENT_NUMBER = Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)$");

    private FormulaValues() {
    }

    /** An error that stops the function being calculated and becomes its value. */
    static final class Signal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient XlError error;

        Signal(XlError error) {
            super(error.code(), null, false, false);
            this.error = error;
        }

        XlError error() {
            return error;
        }
    }

    /** What a whitespace character is, as the browser's trim sees it. */
    static String trim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && space(s.charAt(start))) {
            start++;
        }
        while (end > start && space(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean space(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '﻿';
    }

    static boolean isNumberText(String s) {
        return PLAIN_NUMBER.matcher(s).matches();
    }

    static boolean isPercentBody(String s) {
        return PERCENT_NUMBER.matcher(s).matches();
    }

    /** The number a value stands for; an error value or a text that is no number signals an error. */
    static double number(Object v) {
        if (v instanceof XlError e) {
            throw new Signal(e);
        }
        if (v == null) {
            return 0;
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof Double d) {
            return d;
        }
        String s = trim((String) v);
        if (s.isEmpty()) {
            throw new Signal(XlError.VALUE);
        }
        String plain = s.replace(",", "");
        if (!isNumberText(plain)) {
            throw new Signal(XlError.VALUE);
        }
        return Double.parseDouble(plain);
    }

    static boolean bool(Object v) {
        if (v instanceof XlError e) {
            throw new Signal(e);
        }
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Double d) {
            return d != 0;
        }
        String s = ((String) v).toUpperCase(java.util.Locale.ROOT);
        if (s.equals("TRUE")) {
            return true;
        }
        if (s.equals("FALSE")) {
            return false;
        }
        throw new Signal(XlError.VALUE);
    }

    static String text(Object v) {
        if (v instanceof XlError e) {
            throw new Signal(e);
        }
        if (v == null) {
            return "";
        }
        if (v instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        if (v instanceof Double d) {
            return numberText(d);
        }
        return (String) v;
    }

    private static int rank(Object v) {
        return v instanceof Double ? 1 : v instanceof String ? 2 : 3;
    }

    /**
     * Orders two values as a comparison does: an empty cell takes the kind of the other side, kinds rank by number,
     * text, logical.
     */
    static int compare(Object a, Object b) {
        if (a instanceof XlError e) {
            throw new Signal(e);
        }
        if (b instanceof XlError e) {
            throw new Signal(e);
        }
        Object x = a;
        Object y = b;
        if (x == null) {
            x = y instanceof String ? "" : y instanceof Boolean ? Boolean.FALSE : Double.valueOf(0);
        }
        if (y == null) {
            y = x instanceof String ? "" : x instanceof Boolean ? Boolean.FALSE : Double.valueOf(0);
        }
        if (rank(x) != rank(y)) {
            return rank(x) - rank(y);
        }
        if (x instanceof String s) {
            return Integer.signum(s.toLowerCase(java.util.Locale.ROOT).compareTo(((String) y)
                    .toLowerCase(java.util.Locale.ROOT)));
        }
        if (x instanceof Double d) {
            return d < (Double) y ? -1 : d > (Double) y ? 1 : 0;
        }
        boolean p = (Boolean) x;
        boolean q = (Boolean) y;
        return p == q ? 0 : p ? 1 : -1;
    }

    // ---- showing a number ----

    /** A number as a cell shows it before any format: fifteen significant digits, no trailing zeros. */
    public static String numberText(double n) {
        if (n == 0 || Double.isNaN(n) || Double.isInfinite(n)) {
            return "0";
        }
        double rounded = new BigDecimal(n).round(new MathContext(15, RoundingMode.HALF_UP)).doubleValue();
        BigDecimal v = new BigDecimal(Double.toString(rounded)).stripTrailingZeros();
        int exponent = v.precision() - v.scale() - 1;
        if (exponent >= -6 && exponent < 21) {
            return v.toPlainString();
        }
        String digits = v.unscaledValue().abs().toString();
        String mantissa = digits.length() > 1 ? digits.charAt(0) + "." + digits.substring(1) : digits;
        return (v.signum() < 0 ? "-" : "") + mantissa + "e" + (exponent >= 0 ? "+" : "-") + Math.abs(exponent);
    }

    // ---- rounding ----

    /** x moved by d decimal places without the binary error of a multiplication (2.675 stays 2.675). */
    static double shift(double x, int d) {
        if (x == 0 || Double.isNaN(x) || Double.isInfinite(x)) {
            return x;
        }
        return new BigDecimal(x).round(new MathContext(15, RoundingMode.HALF_UP)).scaleByPowerOfTen(d).doubleValue();
    }

    private static double round(double v) {
        double f = Math.floor(v);
        return v - f >= 0.5 ? f + 1 : f;
    }

    private static int digits(double d) {
        return (int) Math.max(-30, Math.min(30, d < 0 ? Math.ceil(d) : Math.floor(d)));
    }

    static double roundHalfUp(double x, double places) {
        int n = digits(places);
        double sign = x < 0 ? -1 : 1;
        return sign * shift(round(shift(Math.abs(x), n)), -n);
    }

    static double roundAway(double x, double places) {
        int n = digits(places);
        double sign = x < 0 ? -1 : 1;
        return sign * shift(Math.ceil(shift(Math.abs(x), n) - 1e-12), -n);
    }

    static double roundToward(double x, double places) {
        int n = digits(places);
        double sign = x < 0 ? -1 : 1;
        return sign * shift(Math.floor(shift(Math.abs(x), n) + 1e-12), -n);
    }

    static double power(double x, double y) {
        if (x == 0 && y == 0) {
            throw new Signal(XlError.NUM);
        }
        if (x == 0 && y < 0) {
            throw new Signal(XlError.DIV0);
        }
        return finite(Math.pow(x, y));
    }

    /** A result of arithmetic that left the range of numbers is an error, not an infinity. */
    static double finite(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) {
            throw new Signal(XlError.NUM);
        }
        return x;
    }

    /** The number formatted as one of the nine formats of TEXT. */
    static String formatNumber(double x, String format) {
        boolean percent = format.endsWith("%");
        String body = percent ? format.substring(0, format.length() - 1) : format;
        boolean comma = body.contains(",");
        int places = body.contains(".") ? body.length() - body.indexOf('.') - 1 : 0;
        double v = roundHalfUp(percent ? x * 100 : x, places);
        String s = Math.abs(v) >= 1e21 ? numberText(Math.abs(v))
                : new BigDecimal(Math.abs(v)).setScale(places, RoundingMode.HALF_UP).toPlainString();
        if (comma) {
            int dot = s.indexOf('.');
            String whole = dot < 0 ? s : s.substring(0, dot);
            String fraction = dot < 0 ? "" : s.substring(dot);
            StringBuilder grouped = new StringBuilder();
            for (int i = 0; i < whole.length(); i++) {
                if (i > 0 && (whole.length() - i) % 3 == 0) {
                    grouped.append(',');
                }
                grouped.append(whole.charAt(i));
            }
            s = grouped + fraction;
        }
        boolean negative = v < 0 && Double.parseDouble(s.replace(",", "")) != 0;
        return (negative ? "-" : "") + s + (percent ? "%" : "");
    }

    // ---- the criterion of SUMIF, COUNTIF and AVERAGEIF ----

    /** A test on a cell value. */
    interface Test {
        boolean matches(Object value);
    }

    /** The test a criterion stands for: an operator, then a number or a text that may hold * and ?. */
    static Test criterion(Object c) {
        if (c instanceof XlError e) {
            throw new Signal(e);
        }
        if (c instanceof Double d) {
            return v -> v instanceof Double x && x.doubleValue() == d;
        }
        if (c instanceof Boolean b) {
            return v -> v instanceof Boolean x && x.equals(b);
        }
        if (c == null) {
            return v -> v instanceof Double x && x == 0;
        }
        String s = (String) c;
        String op = "=";
        for (String o : new String[] {">=", "<=", "<>", "=", ">", "<"}) {
            if (s.startsWith(o)) {
                op = o;
                s = s.substring(o.length());
                break;
            }
        }
        String rest = s;
        String trimmed = trim(rest);
        if (!trimmed.isEmpty() && isNumberText(trimmed)) {
            double number = Double.parseDouble(rest);
            String operator = op;
            return v -> {
                boolean numeric = v instanceof Double;
                double x = numeric ? (Double) v : 0;
                return switch (operator) {
                    case "=" -> numeric && x == number;
                    case "<>" -> !(numeric && x == number);
                    case ">" -> numeric && x > number;
                    case ">=" -> numeric && x >= number;
                    case "<" -> numeric && x < number;
                    default -> numeric && x <= number;
                };
            };
        }
        String pattern = rest.toLowerCase(java.util.Locale.ROOT);
        String operator = op;
        if (operator.equals("=")) {
            return v -> v instanceof String t && glob(pattern, t.toLowerCase(java.util.Locale.ROOT));
        }
        if (operator.equals("<>")) {
            return v -> !(v instanceof String t && glob(pattern, t.toLowerCase(java.util.Locale.ROOT)));
        }
        return v -> {
            if (!(v instanceof String t)) {
                return false;
            }
            int order = t.toLowerCase(java.util.Locale.ROOT).compareTo(pattern);
            return switch (operator) {
                case ">" -> order > 0;
                case ">=" -> order >= 0;
                case "<" -> order < 0;
                default -> order <= 0;
            };
        };
    }

    /** Whether the text matches the pattern, where * is any run and ? any one character (no backtracking blow-up). */
    static boolean glob(String pattern, String text) {
        int p = 0;
        int t = 0;
        int star = -1;
        int mark = 0;
        while (t < text.length()) {
            if (p < pattern.length() && (pattern.charAt(p) == '?' || pattern.charAt(p) == text.charAt(t))
                    && pattern.charAt(p) != '*') {
                p++;
                t++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                mark = t;
            } else if (star >= 0) {
                p = star + 1;
                t = ++mark;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }
}
