package app.platform.expression.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How numbers behave in an expression (ADR-0076): decimals, never floating point, so that {@code 0.1 + 0.2} is
 * {@code 0.3}. A number has at most {@value #MAX_INTEGER_DIGITS} digits before the point and
 * {@value #MAX_SCALE} after it; a result with more fraction digits is rounded half away from zero, a result with more
 * integer digits is an overflow error and never a wrapped value. Text is turned into a number by hand, never with an
 * exponent form: {@code 1E999999999} must not be a number.
 */
final class Numbers {

    static final int MAX_INTEGER_DIGITS = 28;
    static final int MAX_SCALE = 10;

    private Numbers() {
    }

    /** A number without trailing zeros and never with a negative scale, so equal numbers are equal objects. */
    static BigDecimal normal(BigDecimal value) {
        BigDecimal stripped = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /** How many digits the number has before the point (at least one). */
    static int integerDigits(BigDecimal value) {
        return Math.max(1, value.precision() - value.scale());
    }

    /** Whether the number fits: at most {@value #MAX_INTEGER_DIGITS} digits before the point. */
    static boolean fits(BigDecimal value) {
        return integerDigits(value) <= MAX_INTEGER_DIGITS;
    }

    /** The result of an operation, rounded to the largest scale; null when it has too many integer digits. */
    static BigDecimal settle(BigDecimal value) {
        BigDecimal rounded = value.scale() > MAX_SCALE ? value.setScale(MAX_SCALE, RoundingMode.HALF_UP) : value;
        return fits(rounded) ? normal(rounded) : null;
    }

    /**
     * Reads a number written as an optional sign, digits, and optionally a point and digits, and nothing else; returns
     * null for any other text and for a number that has too many digits.
     *
     * @param allowSign whether a leading {@code -} or {@code +} is part of the number
     */
    static BigDecimal parse(String text, boolean allowSign) {
        int length = text.length();
        int i = 0;
        boolean negative = false;
        if (allowSign && i < length && (text.charAt(i) == '-' || text.charAt(i) == '+')) {
            negative = text.charAt(i) == '-';
            i++;
        }
        int wholeStart = i;
        while (i < length && isDigit(text.charAt(i))) {
            i++;
        }
        int wholeEnd = i;
        if (wholeEnd == wholeStart) {
            return null;
        }
        int fractionStart = i;
        if (i < length && text.charAt(i) == '.') {
            i++;
            fractionStart = i;
            while (i < length && isDigit(text.charAt(i))) {
                i++;
            }
            if (i == fractionStart) {
                return null;
            }
        }
        if (i != length) {
            return null;
        }
        while (wholeStart < wholeEnd - 1 && text.charAt(wholeStart) == '0') {
            wholeStart++;
        }
        int fractionDigits = i - fractionStart;
        if (wholeEnd - wholeStart > MAX_INTEGER_DIGITS || fractionDigits > MAX_SCALE) {
            return null;
        }
        String digits = fractionDigits == 0 ? text.substring(wholeStart, wholeEnd)
                : text.substring(wholeStart, wholeEnd) + "." + text.substring(fractionStart, i);
        BigDecimal value = new BigDecimal(digits);
        return normal(negative ? value.negate() : value);
    }

    /** The number as plain digits, never with an exponent. */
    static String format(BigDecimal value) {
        return normal(value).toPlainString();
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
