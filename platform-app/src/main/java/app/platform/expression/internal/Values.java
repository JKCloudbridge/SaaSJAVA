package app.platform.expression.internal;

import app.platform.expression.ExpressionException;
import app.platform.expression.ExpressionException.Kind;
import app.platform.expression.ExpressionType;
import app.platform.expression.Limits;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * The values an expression works with and how they are read and written (ADR-0076). A value is {@code null} (no value),
 * a {@link BigDecimal}, a {@link String} that is never empty, a {@link Boolean}, a {@link LocalDate} in the years 1 to
 * 9999, an {@link Instant} (UTC, whole seconds) or a {@link LocalTime} (whole seconds). Everything that comes from
 * outside (a value of a record, a sample typed in the setup screen) is checked here before the evaluator sees it, so
 * the evaluator can trust what it holds.
 */
final class Values {

    private static final int FIRST_YEAR = 1;
    private static final int LAST_YEAR = 9999;
    private static final int TEXT_FORMAT_MAX = 100;
    private static final Instant FIRST_MOMENT = LocalDate.of(FIRST_YEAR, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
    private static final Instant END_MOMENT = LocalDate.of(LAST_YEAR, 12, 31).plusDays(1).atStartOfDay(ZoneOffset.UTC)
            .toInstant();

    private Values() {
    }

    /** The text, or null when it is empty: the empty text is no value. */
    static String text(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** Whether the date is a day the language allows (years 1 to 9999). */
    static boolean inRange(LocalDate date) {
        return date.getYear() >= FIRST_YEAR && date.getYear() <= LAST_YEAR;
    }

    /** Whether the moment is one the language allows (years 1 to 9999, UTC). */
    static boolean inRange(Instant instant) {
        return !instant.isBefore(FIRST_MOMENT) && instant.isBefore(END_MOMENT);
    }

    /**
     * Checks a value that comes from outside against the type of its field and returns it in the form the evaluator
     * uses, or null for no value.
     *
     * @throws ExpressionException {@code INVALID_VALUE}, without the position (the caller adds it)
     */
    static Object external(ExpressionType type, Object given, Limits limits, String field) {
        if (given == null) {
            return null;
        }
        return switch (type) {
            case NUMBER -> number(given, field);
            case TEXT -> {
                if (!(given instanceof String text)) {
                    throw invalid(field, "is not text");
                }
                if (text.length() > limits.maxTextLength()) {
                    throw invalid(field, "is longer than " + limits.maxTextLength() + " characters");
                }
                yield text(text);
            }
            case BOOLEAN -> {
                if (!(given instanceof Boolean flag)) {
                    throw invalid(field, "is not true or false");
                }
                yield flag;
            }
            case DATE -> {
                if (!(given instanceof LocalDate date) || !inRange(date)) {
                    throw invalid(field, "is not a date between the years 1 and 9999");
                }
                yield date;
            }
            case DATETIME -> {
                if (!(given instanceof Instant instant) || !inRange(instant)) {
                    throw invalid(field, "is not a date and time between the years 1 and 9999");
                }
                yield instant.truncatedTo(ChronoUnit.SECONDS);
            }
            case TIME -> {
                if (!(given instanceof LocalTime time)) {
                    throw invalid(field, "is not a time");
                }
                yield time.truncatedTo(ChronoUnit.SECONDS);
            }
            default -> throw invalid(field, "has no type an expression can use");
        };
    }

    private static BigDecimal number(Object given, String field) {
        BigDecimal value;
        if (given instanceof BigDecimal decimal) {
            value = decimal;
        } else if (given instanceof Integer || given instanceof Long || given instanceof Short
                || given instanceof Byte) {
            value = BigDecimal.valueOf(((Number) given).longValue());
        } else {
            throw invalid(field, "is not a number");
        }
        // Checked before anything is done with it: a number like 1E+999999999 has one digit and a huge exponent.
        if (value.scale() > Numbers.MAX_SCALE || value.scale() < -Numbers.MAX_INTEGER_DIGITS
                || !Numbers.fits(value)) {
            throw invalid(field, "has more digits than an expression can use");
        }
        return Numbers.normal(value);
    }

    private static ExpressionException invalid(String field, String why) {
        return new ExpressionException(Kind.INVALID_VALUE, null, "The value given for the field " + field + " " + why
                + ".");
    }

    // ---- reading a value written as text (the sample values of the test box, defaults) ----

    /**
     * Reads a value written as text for a type: a number as plain digits, true or false, a date as 2030-01-31, a date
     * and time as 2030-01-31T09:30:00Z, a time as 09:30 or 09:30:15, any text as it is. Blank text is no value.
     *
     * @throws ExpressionException {@code INVALID_VALUE}
     */
    static Object parse(ExpressionType type, String written, Limits limits, String field) {
        if (written == null || written.isBlank() && type != ExpressionType.TEXT) {
            return null;
        }
        if (type != ExpressionType.TEXT && written.length() > TEXT_FORMAT_MAX) {
            throw invalid(field, "is too long to be a value of its type");
        }
        try {
            return switch (type) {
                case TEXT -> external(type, written, limits, field);
                case NUMBER -> {
                    BigDecimal number = Numbers.parse(written.strip(), true);
                    if (number == null) {
                        throw invalid(field, "is not a number written in plain digits (at most "
                                + Numbers.MAX_INTEGER_DIGITS + " before the point and " + Numbers.MAX_SCALE
                                + " after it)");
                    }
                    yield number;
                }
                case BOOLEAN -> {
                    String flag = written.strip().toLowerCase(Locale.ROOT);
                    if (!flag.equals("true") && !flag.equals("false")) {
                        throw invalid(field, "is not true or false");
                    }
                    yield Boolean.valueOf(flag);
                }
                case DATE -> external(type, LocalDate.parse(written.strip()), limits, field);
                case DATETIME -> external(type, Instant.parse(written.strip()), limits, field);
                case TIME -> external(type, LocalTime.parse(written.strip()), limits, field);
                default -> throw invalid(field, "has no type an expression can use");
            };
        } catch (DateTimeException e) {
            throw invalid(field, "is not written in the form of its type (a date like 2030-01-31, a date and time like "
                    + "2030-01-31T09:30:00Z, a time like 09:30)");
        }
    }

    // ---- writing a value as text ----

    /** The value as text: plain digits, true or false, ISO dates and times. Null for no value. */
    static String format(Object value) {
        return switch (value) {
            case null -> null;
            case BigDecimal number -> Numbers.format(number);
            case Boolean flag -> flag ? "true" : "false";
            case LocalDate date -> date.toString();
            case Instant instant -> instant.truncatedTo(ChronoUnit.SECONDS).toString();
            case LocalTime time -> {
                LocalTime whole = time.truncatedTo(ChronoUnit.SECONDS);
                yield String.format(Locale.ROOT, "%02d:%02d:%02d", whole.getHour(), whole.getMinute(),
                        whole.getSecond());
            }
            default -> value.toString();
        };
    }
}
