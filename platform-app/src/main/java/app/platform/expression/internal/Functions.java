package app.platform.expression.internal;

import app.platform.expression.ExpressionException.Kind;
import app.platform.expression.ExpressionType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The library: the fixed list of functions an expression may call (ADR-0076). Nothing outside this list can be called,
 * whatever the text says, and every function is a plain method on values: none reads a file, the network, the clock
 * (except {@code TODAY} and {@code NOW}, which read the moment the caller gave) or a class.
 *
 * <p>To add a function, add one {@link #define} call below: its name, how many arguments, the words that describe it,
 * a rule that says which types it takes and gives, and the code that runs it; then add it to the guide and to the
 * tests (the recipe is in {@code docs/metadata-guide.md}). Nothing else changes.
 */
final class Functions {

    private static final int MAX_DAYS = 3_660_000;
    private static final int MAX_LIST = 10;
    private static final int MAX_CONCAT = 20;

    /** The rule that decides what a call takes and gives. */
    @FunctionalInterface
    interface Typing {

        ExpressionType type(TypeArgs args);
    }

    /** The code of a function. */
    @FunctionalInterface
    interface Implementation {

        Object apply(Invocation call);
    }

    /**
     * One function.
     *
     * @param name the name in capitals
     * @param min the fewest arguments
     * @param max the most arguments
     * @param signature how a call looks, for people
     * @param description what it does, in plain words
     * @param clock whether it reads the moment of the evaluation (only a validation rule may use it)
     * @param typing the typing rule
     * @param implementation the code
     */
    record Definition(String name, int min, int max, String signature, String description, boolean clock,
            Typing typing, Implementation implementation) {
    }

    private static final Map<String, Definition> LIBRARY = build();

    private Functions() {
    }

    /** The function with this name in capitals, if the library has it. */
    static Optional<Definition> find(String name) {
        return Optional.ofNullable(LIBRARY.get(name));
    }

    /** Every function, in the order of the guide. */
    static List<Definition> all() {
        return List.copyOf(LIBRARY.values());
    }

    private static Map<String, Definition> build() {
        Map<String, Definition> library = new LinkedHashMap<>();
        logic(library);
        numbers(library);
        text(library);
        dates(library);
        return Collections.unmodifiableMap(library);
    }

    private static void define(Map<String, Definition> library, String name, int min, int max, String signature,
            String description, boolean clock, Typing typing, Implementation implementation) {
        library.put(name, new Definition(name, min, max, signature, description, clock, typing, implementation));
    }

    // ---- logic and blanks ----

    private static void logic(Map<String, Definition> library) {
        define(library, "IF", 3, 3, "IF(condition, then, else)",
                "The second value when the condition is true, otherwise the third (also when the condition has no "
                        + "value). Only the value that is chosen is worked out.",
                false, args -> {
                    args.expect(0, ExpressionType.BOOLEAN);
                    return args.unify(1);
                }, call -> {
                    Boolean condition = (Boolean) call.value(0);
                    return Boolean.TRUE.equals(condition) ? call.value(1) : call.value(2);
                });
        define(library, "ISBLANK", 1, 1, "ISBLANK(value)",
                "True when the value is empty: a field nobody filled in, or the empty text. The number 0 and the "
                        + "text \" \" are values.",
                false, args -> ExpressionType.BOOLEAN, call -> call.value(0) == null);
        define(library, "COALESCE", 2, MAX_LIST, "COALESCE(value, value, ...)",
                "The first of the values that is not empty; empty when all are. They must be of the same type.",
                false, args -> args.unify(0), call -> {
                    for (int i = 0; i < call.count(); i++) {
                        Object value = call.value(i);
                        if (value != null) {
                            return value;
                        }
                    }
                    return null;
                });
    }

    // ---- numbers ----

    private static void numbers(Map<String, Definition> library) {
        define(library, "ABS", 1, 1, "ABS(number)", "The number without its sign.", false, number(1),
                call -> {
                    BigDecimal value = call.number(0);
                    return value == null ? null : value.abs();
                });
        define(library, "ROUND", 2, 2, "ROUND(number, places)",
                "The number rounded to the given number of decimal places (0 to 10); halves round away from zero.",
                false, number(2), call -> {
                    BigDecimal value = call.number(0);
                    Integer places = call.whole(1, 0, Numbers.MAX_SCALE, "the number of decimal places");
                    return value == null || places == null ? null
                            : Numbers.normal(value.setScale(places, RoundingMode.HALF_UP));
                });
        define(library, "FLOOR", 1, 1, "FLOOR(number)", "The largest whole number that is not more than the number.",
                false, number(1), call -> {
                    BigDecimal value = call.number(0);
                    return value == null ? null : Numbers.normal(value.setScale(0, RoundingMode.FLOOR));
                });
        define(library, "CEILING", 1, 1, "CEILING(number)",
                "The smallest whole number that is not less than the number.", false, number(1), call -> {
                    BigDecimal value = call.number(0);
                    return value == null ? null : Numbers.normal(value.setScale(0, RoundingMode.CEILING));
                });
        define(library, "MIN", 2, MAX_LIST, "MIN(number, number, ...)",
                "The smallest of the numbers; empty when any of them is empty.", false,
                numbersOnly(), call -> extreme(call, -1));
        define(library, "MAX", 2, MAX_LIST, "MAX(number, number, ...)",
                "The largest of the numbers; empty when any of them is empty.", false,
                numbersOnly(), call -> extreme(call, 1));
    }

    private static Typing number(int count) {
        return numbersGiving(count, ExpressionType.NUMBER);
    }

    /** The rule of a function that takes {@code count} numbers and gives a value of the type. */
    private static Typing numbersGiving(int count, ExpressionType result) {
        return args -> {
            for (int i = 0; i < count; i++) {
                args.expect(i, ExpressionType.NUMBER);
            }
            return result;
        };
    }

    private static Typing numbersOnly() {
        return args -> {
            for (int i = 0; i < args.count(); i++) {
                args.expect(i, ExpressionType.NUMBER);
            }
            return ExpressionType.NUMBER;
        };
    }

    private static Object extreme(Invocation call, int direction) {
        BigDecimal best = null;
        for (int i = 0; i < call.count(); i++) {
            BigDecimal value = call.number(i);
            if (value == null) {
                return null;
            }
            if (best == null || value.compareTo(best) * direction > 0) {
                best = value;
            }
        }
        return best;
    }

    // ---- text ----

    private static void text(Map<String, Definition> library) {
        define(library, "LEN", 1, 1, "LEN(text)", "The number of characters of the text; 0 for an empty text.", false,
                args -> {
                    args.expect(0, ExpressionType.TEXT);
                    return ExpressionType.NUMBER;
                }, call -> {
                    String text = call.text(0);
                    if (text == null) {
                        return BigDecimal.ZERO;
                    }
                    call.chargeText(text.length());
                    return BigDecimal.valueOf(text.codePointCount(0, text.length()));
                });
        define(library, "UPPER", 1, 1, "UPPER(text)", "The text in capital letters.", false, textToText(1),
                call -> {
                    String text = call.text(0);
                    if (text == null) {
                        return null;
                    }
                    call.chargeText(text.length());
                    return call.result(text.toUpperCase(Locale.ROOT));
                });
        define(library, "LOWER", 1, 1, "LOWER(text)", "The text in small letters.", false, textToText(1),
                call -> {
                    String text = call.text(0);
                    if (text == null) {
                        return null;
                    }
                    call.chargeText(text.length());
                    return call.result(text.toLowerCase(Locale.ROOT));
                });
        define(library, "TRIM", 1, 1, "TRIM(text)", "The text without spaces at the start and the end.", false,
                textToText(1), call -> {
                    String text = call.text(0);
                    if (text == null) {
                        return null;
                    }
                    call.chargeText(text.length());
                    return call.result(text.strip());
                });
        define(library, "LEFT", 2, 2, "LEFT(text, count)", "The first characters of the text.", false,
                cut(2), call -> {
                    String text = call.text(0);
                    Integer count = call.whole(1, 0, call.maxText(), "the number of characters");
                    if (text == null || count == null) {
                        return null;
                    }
                    call.chargeText(text.length());
                    return call.result(TextSearch.left(text, count));
                });
        define(library, "RIGHT", 2, 2, "RIGHT(text, count)", "The last characters of the text.", false,
                cut(2), call -> {
                    String text = call.text(0);
                    Integer count = call.whole(1, 0, call.maxText(), "the number of characters");
                    if (text == null || count == null) {
                        return null;
                    }
                    call.chargeText(text.length());
                    return call.result(TextSearch.right(text, count));
                });
        define(library, "SUBSTRING", 3, 3, "SUBSTRING(text, start, length)",
                "Part of the text: the given number of characters from the position start, where the first character "
                        + "is position 1.",
                false, cut(3), call -> {
                    String text = call.text(0);
                    Integer start = call.whole(1, 1, call.maxText(), "the start position");
                    Integer length = call.whole(2, 0, call.maxText(), "the number of characters");
                    if (text == null || start == null || length == null) {
                        return null;
                    }
                    call.chargeText(text.length());
                    return call.result(TextSearch.substring(text, start, length));
                });
        define(library, "CONTAINS", 2, 2, "CONTAINS(text, part)",
                "True when the text contains the part (capital and small letters count as different letters); false "
                        + "when either has no value.",
                false, textTest(), call -> {
                    String text = call.text(0);
                    String part = call.text(1);
                    if (text == null || part == null) {
                        return false;
                    }
                    call.chargeText(text.length() + part.length());
                    return TextSearch.contains(text, part);
                });
        define(library, "STARTS_WITH", 2, 2, "STARTS_WITH(text, start)",
                "True when the text begins with the start; false when either has no value.", false, textTest(),
                call -> {
                    String text = call.text(0);
                    String start = call.text(1);
                    return text != null && start != null && text.startsWith(start);
                });
        define(library, "ENDS_WITH", 2, 2, "ENDS_WITH(text, end)",
                "True when the text ends with the end; false when either has no value.", false, textTest(),
                call -> {
                    String text = call.text(0);
                    String end = call.text(1);
                    return text != null && end != null && text.endsWith(end);
                });
        define(library, "CONCAT", 2, MAX_CONCAT, "CONCAT(text, text, ...)",
                "The texts joined into one; a text with no value counts as nothing.", false, args -> {
                    for (int i = 0; i < args.count(); i++) {
                        args.expect(i, ExpressionType.TEXT);
                    }
                    return ExpressionType.TEXT;
                }, Functions::concat);
        define(library, "TEXT", 1, 1, "TEXT(value)",
                "Any value as text: numbers in plain digits, true or false, dates like 2030-01-31, a date and time "
                        + "like 2030-01-31T09:30:00Z.",
                false, args -> ExpressionType.TEXT, call -> {
                    Object value = call.value(0);
                    return value == null ? null : call.result(Values.format(value));
                });
        define(library, "VALUE", 1, 1, "VALUE(text)",
                "The number written in the text (digits with an optional sign and point); an error when the text is "
                        + "not a number.",
                false, args -> {
                    args.expect(0, ExpressionType.TEXT);
                    return ExpressionType.NUMBER;
                }, call -> {
                    String text = call.text(0);
                    if (text == null) {
                        return null;
                    }
                    BigDecimal number = text.length() > 100 ? null : Numbers.parse(text.strip(), true);
                    if (number == null) {
                        throw call.failAt(0, Kind.NOT_A_NUMBER, "This text is not a number (plain digits with an "
                                + "optional sign and decimal point).");
                    }
                    return number;
                });
    }

    private static Typing textToText(int count) {
        return args -> {
            for (int i = 0; i < count; i++) {
                args.expect(i, ExpressionType.TEXT);
            }
            return ExpressionType.TEXT;
        };
    }

    private static Typing cut(int count) {
        return args -> {
            args.expect(0, ExpressionType.TEXT);
            for (int i = 1; i < count; i++) {
                args.expect(i, ExpressionType.NUMBER);
            }
            return ExpressionType.TEXT;
        };
    }

    private static Typing textTest() {
        return args -> {
            args.expect(0, ExpressionType.TEXT);
            args.expect(1, ExpressionType.TEXT);
            return ExpressionType.BOOLEAN;
        };
    }

    private static Object concat(Invocation call) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < call.count(); i++) {
            String part = call.text(i);
            if (part != null) {
                if (joined.length() + (long) part.length() > call.maxText()) {
                    throw call.fail(Kind.TEXT_TOO_LONG, "The text this makes is longer than " + call.maxText()
                            + " characters.");
                }
                joined.append(part);
                call.chargeText(part.length());
            }
        }
        return call.result(joined.toString());
    }

    // ---- dates ----

    private static void dates(Map<String, Definition> library) {
        define(library, "DATE", 3, 3, "DATE(year, month, day)", "The date with the given year, month and day.", false,
                numbersGiving(3, ExpressionType.DATE), call -> {
                    Integer year = call.whole(0, 1, 9999, "the year");
                    Integer month = call.whole(1, 1, 12, "the month");
                    Integer day = call.whole(2, 1, 31, "the day");
                    if (year == null || month == null || day == null) {
                        return null;
                    }
                    try {
                        return LocalDate.of(year, month, day);
                    } catch (DateTimeException e) {
                        throw call.fail(Kind.OUT_OF_RANGE, "There is no such day in the calendar.");
                    }
                });
        define(library, "YEAR", 1, 1, "YEAR(date)", "The year of a date or of a date and time.", false, dayPart(),
                call -> dayOf(call).map(day -> BigDecimal.valueOf(day.getYear())).orElse(null));
        define(library, "MONTH", 1, 1, "MONTH(date)", "The month (1 to 12) of a date or of a date and time.", false,
                dayPart(), call -> dayOf(call).map(day -> BigDecimal.valueOf(day.getMonthValue())).orElse(null));
        define(library, "DAY", 1, 1, "DAY(date)", "The day of the month of a date or of a date and time.", false,
                dayPart(), call -> dayOf(call).map(day -> BigDecimal.valueOf(day.getDayOfMonth())).orElse(null));
        define(library, "ADD_DAYS", 2, 2, "ADD_DAYS(date, days)",
                "The date or the date and time moved by a whole number of days (negative moves it back).", false,
                args -> {
                    args.expect(0, ExpressionType.DATE, ExpressionType.DATETIME);
                    args.expect(1, ExpressionType.NUMBER);
                    return args.type(0) == ExpressionType.DATETIME ? ExpressionType.DATETIME : ExpressionType.DATE;
                }, Functions::addDays);
        define(library, "DAYS_BETWEEN", 2, 2, "DAYS_BETWEEN(from, to)",
                "The number of whole calendar days from the first date to the second (negative when the second is "
                        + "earlier). A date and time counts as its day in UTC.",
                false, args -> {
                    args.expect(0, ExpressionType.DATE, ExpressionType.DATETIME);
                    args.expect(1, ExpressionType.DATE, ExpressionType.DATETIME);
                    return ExpressionType.NUMBER;
                }, call -> {
                    Object from = call.value(0);
                    Object to = call.value(1);
                    if (from == null || to == null) {
                        return null;
                    }
                    return BigDecimal.valueOf(ChronoUnit.DAYS.between(day(from), day(to)));
                });
        define(library, "TODAY", 0, 0, "TODAY()", "Today's date at the moment the record is saved (validation rules "
                + "only).", true, args -> ExpressionType.DATE, call -> Evaluator.dayOf(call.now()));
        define(library, "NOW", 0, 0, "NOW()", "The date and time at the moment the record is saved (validation rules "
                + "only).", true, args -> ExpressionType.DATETIME, call -> call.now());
    }

    private static Typing dayPart() {
        return args -> {
            args.expect(0, ExpressionType.DATE, ExpressionType.DATETIME);
            return ExpressionType.NUMBER;
        };
    }

    private static Optional<LocalDate> dayOf(Invocation call) {
        Object value = call.value(0);
        return value == null ? Optional.empty() : Optional.of(day(value));
    }

    private static LocalDate day(Object value) {
        return value instanceof Instant moment ? Evaluator.dayOf(moment) : (LocalDate) value;
    }

    private static Object addDays(Invocation call) {
        Object value = call.value(0);
        Integer days = call.whole(1, -MAX_DAYS, MAX_DAYS, "the number of days");
        if (value == null || days == null) {
            return null;
        }
        if (value instanceof Instant moment) {
            Instant moved = moment.plus(days, ChronoUnit.DAYS);
            if (!Values.inRange(moved)) {
                throw call.fail(Kind.OUT_OF_RANGE, "The result is a date outside the years 1 to 9999.");
            }
            return moved;
        }
        LocalDate moved = ((LocalDate) value).plusDays(days);
        if (!Values.inRange(moved)) {
            throw call.fail(Kind.OUT_OF_RANGE, "The result is a date outside the years 1 to 9999.");
        }
        return moved;
    }

    /** The names of every function, in the order of the guide (for tests and the guide). */
    static List<String> names() {
        List<String> names = new ArrayList<>();
        LIBRARY.values().forEach(definition -> names.add(definition.name()));
        return names;
    }
}
