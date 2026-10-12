package app.platform.data.internal;

import app.platform.metadata.FieldConfiguration.DecimalConfiguration;
import app.platform.metadata.FieldConfiguration.FormulaConfiguration;
import app.platform.metadata.FieldConfiguration.NumberConfiguration;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.PicklistValue;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What a value must be to belong in a field of each type (ADR-0091), in one place: the check of a value typed by a
 * person, the conversion of a stored value to what the expression engine takes, and the conversion of what the engine
 * gives back to the stored shape. The rules are those of {@code FieldRules} in the metadata module (which decides what
 * a default value may be), so a default that was accepted when the field was saved is a value that passes here.
 *
 * <p>Stored shapes (the payload is JSON, ADR-0084): a text is a string; numbers of every kind are decimals; a date is
 * {@code 2030-01-31}, a date and time {@code 2030-01-31T09:30:00Z} (always UTC), a time {@code 09:30}; a picklist is
 * its value; a multi-picklist is a list of values; a lookup and a master-detail are the identifier of the other record
 * as text. Every message says what is expected and never repeats what was typed.
 */
final class FieldValue {

    /** The most characters of an e-mail address, a telephone number and a web address. */
    static final int EMAIL_LENGTH = 254;
    static final int PHONE_LENGTH = 40;
    static final int URL_LENGTH = 2048;

    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private static final Pattern PHONE = Pattern.compile("[0-9+()\\-./ ]{3,40}");
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    /**
     * The verdict on one value.
     *
     * @param value the value in its stored shape, or null when there is a problem
     * @param problem why the value does not belong in the field, or null
     */
    record Outcome(Object value, String problem) {

        static Outcome ok(Object value) {
            return new Outcome(value, null);
        }

        static Outcome bad(String problem) {
            return new Outcome(null, problem);
        }

        boolean valid() {
            return problem == null;
        }
    }

    private FieldValue() {
    }

    /**
     * Checks a value typed for a field.
     *
     * @param field the field
     * @param raw the value as it arrived (already of a safe shape: text, decimal, true or false, or a list of those)
     * @param current the value the record holds now, or null: a picklist value that has been switched off is still
     *        accepted where the record already holds it (existing records keep reading and saving)
     */
    static Outcome check(FieldDefinition field, Object raw, Object current) {
        return switch (field.type()) {
            case TEXT, LONG_TEXT -> text(raw, ((TextConfiguration) field.configuration()).maxLength());
            case EMAIL -> pattern(raw, EMAIL, EMAIL_LENGTH, "Enter an e-mail address.");
            case PHONE -> pattern(raw, PHONE, PHONE_LENGTH, "Enter a telephone number.");
            case URL -> pattern(raw, URL, URL_LENGTH, "Enter a web address starting with http:// or https://.");
            case NUMBER -> whole(raw, ((NumberConfiguration) field.configuration()).digits());
            case DECIMAL, CURRENCY, PERCENT -> decimal(raw, (DecimalConfiguration) field.configuration());
            case BOOLEAN -> raw instanceof Boolean ? Outcome.ok(raw) : Outcome.bad("Enter true or false.");
            case DATE -> date(raw);
            case DATETIME -> dateTime(raw);
            case TIME -> time(raw);
            case PICKLIST -> picklist(raw, (PicklistConfiguration) field.configuration(), current);
            case MULTI_PICKLIST -> multiPicklist(raw, (PicklistConfiguration) field.configuration(), current);
            case LOOKUP, MASTER_DETAIL -> reference(raw);
            default -> Outcome.bad("This field cannot be written.");
        };
    }

    /**
     * The value a default stands for, in its stored shape; null when the field has no usable default. A default is
     * written as text (FieldRules): a multi-picklist default separates its values with semicolons.
     */
    static Object defaultOf(FieldDefinition field) {
        String text = field.defaultValue();
        if (text == null || text.isEmpty()) {
            return null;
        }
        return switch (field.type()) {
            case NUMBER, DECIMAL, CURRENCY, PERCENT -> parseDecimal(text);
            case BOOLEAN -> Boolean.valueOf(text);
            case MULTI_PICKLIST -> List.of(text.split(";", -1));
            case LOOKUP, MASTER_DETAIL, FORMULA, AUTO_NUMBER -> null;
            default -> text;
        };
    }

    /** The type that decides how the stored value is read: a formula field has the type its formula gives. */
    static FieldType effectiveType(FieldDefinition field) {
        return field.configuration() instanceof FormulaConfiguration formula ? formula.resultType() : field.type();
    }

    /**
     * A stored value as the expression engine takes it ({@code BigDecimal}, {@code String}, {@code Boolean},
     * {@code LocalDate}, {@code Instant}, {@code LocalTime}), or null when there is no usable value. A record written
     * before the pipeline existed may hold a value of the wrong kind; it reads as no value.
     */
    static Object toEngine(FieldDefinition field, Object stored) {
        if (stored == null) {
            return null;
        }
        try {
            return switch (effectiveType(field)) {
                case NUMBER, DECIMAL, CURRENCY, PERCENT -> stored instanceof Number number ? decimalOf(number) : null;
                case BOOLEAN -> stored instanceof Boolean ? stored : null;
                case DATE -> stored instanceof String text ? LocalDate.parse(text) : null;
                case DATETIME -> stored instanceof String text ? Instant.parse(text) : null;
                case TIME -> stored instanceof String text ? LocalTime.parse(text) : null;
                case MULTI_PICKLIST -> stored instanceof List<?> list
                        ? String.join(";", list.stream().map(String::valueOf).toList()) : null;
                default -> stored instanceof String ? stored : null;
            };
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** What the engine gave back, in the stored shape; null when the engine's value cannot be stored. */
    static Object fromEngine(Object calculated) {
        return switch (calculated) {
            case null -> null;
            case BigDecimal number -> storable(number) ? number : null;
            case LocalDate date -> date.toString();
            case Instant instant -> instant.toString();
            case LocalTime time -> time.toString();
            case String text -> text.length() <= RecordValues.MAX_TEXT_LENGTH && text.indexOf('\u0000') < 0
                    ? text : null;
            case Boolean flag -> flag;
            default -> null;
        };
    }

    // ---- the checks ----

    private static Outcome text(Object raw, int maxLength) {
        if (!(raw instanceof String text)) {
            return Outcome.bad("Enter text.");
        }
        return text.length() <= maxLength ? Outcome.ok(text)
                : Outcome.bad("Is longer than the " + maxLength + " characters this field allows.");
    }

    private static Outcome pattern(Object raw, Pattern shape, int maxLength, String expected) {
        if (!(raw instanceof String text) || text.length() > maxLength || !shape.matcher(text).matches()) {
            return Outcome.bad(expected);
        }
        return Outcome.ok(text);
    }

    private static Outcome whole(Object raw, int digits) {
        if (!(raw instanceof BigDecimal number)) {
            return Outcome.bad("Enter a number.");
        }
        BigDecimal trimmed = number.signum() == 0 ? BigDecimal.ZERO : number.stripTrailingZeros();
        if (trimmed.scale() > 0) {
            return Outcome.bad("Enter a whole number.");
        }
        BigInteger integer = trimmed.toBigInteger();
        if (integer.abs().toString().length() > digits) {
            return Outcome.bad("Has more than the " + digits + " digits this field allows.");
        }
        return Outcome.ok(new BigDecimal(integer));
    }

    private static Outcome decimal(Object raw, DecimalConfiguration configuration) {
        if (!(raw instanceof BigDecimal number)) {
            return Outcome.bad("Enter a number.");
        }
        BigDecimal trimmed = number.signum() == 0 ? BigDecimal.ZERO : number.stripTrailingZeros();
        int fraction = Math.max(trimmed.scale(), 0);
        int whole = Math.max(trimmed.precision() - trimmed.scale(), 1);
        if (fraction > configuration.scale()) {
            return Outcome.bad("Has more than the " + configuration.scale() + " decimals this field allows.");
        }
        int wholeDigits = configuration.precision() - configuration.scale();
        if (whole > Math.max(wholeDigits, 1) && trimmed.signum() != 0) {
            return Outcome.bad("Has more than the " + wholeDigits + " digits before the decimal point this field "
                    + "allows.");
        }
        return Outcome.ok(number);
    }

    private static Outcome date(Object raw) {
        try {
            return raw instanceof String text ? Outcome.ok(LocalDate.parse(text).toString())
                    : Outcome.bad("Enter a date written like 2030-01-31.");
        } catch (DateTimeParseException e) {
            return Outcome.bad("Enter a date written like 2030-01-31.");
        }
    }

    private static Outcome dateTime(Object raw) {
        try {
            return raw instanceof String text ? Outcome.ok(Instant.parse(text).toString())
                    : Outcome.bad("Enter a date and time in UTC written like 2030-01-31T09:30:00Z.");
        } catch (DateTimeParseException e) {
            return Outcome.bad("Enter a date and time in UTC written like 2030-01-31T09:30:00Z.");
        }
    }

    private static Outcome time(Object raw) {
        try {
            return raw instanceof String text ? Outcome.ok(LocalTime.parse(text).toString())
                    : Outcome.bad("Enter a time written like 09:30 or 09:30:15.");
        } catch (DateTimeParseException e) {
            return Outcome.bad("Enter a time written like 09:30 or 09:30:15.");
        }
    }

    private static Outcome picklist(Object raw, PicklistConfiguration configuration, Object current) {
        if (!(raw instanceof String value)) {
            return Outcome.bad("Choose one of the values of the list.");
        }
        return choosable(configuration.values(), value, value.equals(current)) ? Outcome.ok(value)
                : Outcome.bad("Choose one of the active values of the list.");
    }

    private static Outcome multiPicklist(Object raw, PicklistConfiguration configuration, Object current) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return Outcome.bad("Choose at least one value of the list, or clear the field.");
        }
        Set<Object> held = current instanceof List<?> now ? new HashSet<>(now) : Set.of();
        Set<String> chosen = new HashSet<>();
        List<String> values = new ArrayList<>();
        for (Object element : list) {
            if (!(element instanceof String value) || !chosen.add(value)
                    || !choosable(configuration.values(), value, held.contains(value))) {
                return Outcome.bad("Choose active values of the list, each once.");
            }
            values.add(value);
        }
        return Outcome.ok(List.copyOf(values));
    }

    private static boolean choosable(List<PicklistValue> defined, String value, boolean alreadyHeld) {
        return defined.stream().anyMatch(known -> known.value().equals(value) && (known.active() || alreadyHeld));
    }

    private static Outcome reference(Object raw) {
        if (!(raw instanceof String text)) {
            return Outcome.bad("Give the identifier of the linked record.");
        }
        try {
            return Outcome.ok(UUID.fromString(text).toString());
        } catch (IllegalArgumentException e) {
            return Outcome.bad("Give the identifier of the linked record.");
        }
    }

    // ---- conversions ----

    private static BigDecimal parseDecimal(String text) {
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal decimalOf(Number number) {
        if (number instanceof BigDecimal exact) {
            return exact;
        }
        if (number instanceof BigInteger big) {
            return new BigDecimal(big);
        }
        return new BigDecimal(number.toString());
    }

    private static boolean storable(BigDecimal number) {
        return number.signum() == 0 || (number.precision() <= 38 && Math.abs(number.scale()) <= 38);
    }
}
