package app.platform.metadata.internal;

import app.platform.metadata.DeleteBehaviour;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldConfiguration.AutoNumberConfiguration;
import app.platform.metadata.FieldConfiguration.DecimalConfiguration;
import app.platform.metadata.FieldConfiguration.FormulaConfiguration;
import app.platform.metadata.FieldConfiguration.NoConfiguration;
import app.platform.metadata.FieldConfiguration.NumberConfiguration;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldType;
import app.platform.metadata.PicklistValue;
import app.platformapi.ApiException;
import app.platformapi.FieldSettings;
import app.platformapi.PicklistOption;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The rules of every field type in one place (ADR-0060): which settings a type takes and their ranges, which
 * constraints it allows, and whether a default value fits. The same rules check what an organization sends and what the
 * platform's own definition files say, so a standard field and a custom field cannot differ in what they may be.
 *
 * <p>Problems are collected and reported together, each under the name of the request field it is about, in words a
 * person can act on; nothing typed is repeated in a message.
 */
final class FieldRules {

    static final int TEXT_DEFAULT = 255;
    static final int LONG_TEXT_DEFAULT = 32_000;
    static final int LONG_TEXT_MAX = 100_000;
    static final int EMAIL_LENGTH = 254;
    static final int PHONE_LENGTH = 40;
    static final int URL_LENGTH = 2048;
    static final int NUMBER_DIGITS = 18;
    static final int PRECISION_MAX = 18;
    static final int AUTO_NUMBER_WIDTH_DEFAULT = 6;
    static final int AUTO_NUMBER_WIDTH_MAX = 12;
    static final int AUTO_NUMBER_PREFIX_MAX = 10;
    static final long AUTO_NUMBER_START_MAX = 999_999_999_999L;
    static final int FORMULA_MAX = 4000;
    static final int MASTER_DETAIL_PER_OBJECT = 2;
    static final int PICKLIST_VALUE_MAX = 80;

    /** The types a formula can yield. */
    static final Set<FieldType> FORMULA_RESULTS = Set.of(FieldType.TEXT, FieldType.NUMBER, FieldType.DECIMAL,
            FieldType.CURRENCY, FieldType.PERCENT, FieldType.BOOLEAN, FieldType.DATE, FieldType.DATETIME,
            FieldType.TIME);

    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private static final Pattern PHONE = Pattern.compile("[0-9+()\\-./ ]{3,40}");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9_-]{0,10}");

    private FieldRules() {
    }

    /**
     * What the rules need to know about the world around the field.
     *
     * @param targetExists whether an object with this API name exists for the organization (a lookup target)
     * @param ownerApiName the object the field belongs to
     * @param masterDetailAllowed whether the object may have master-detail fields (custom objects and the platform's
     *        own definitions may; a custom field added to a standard object may not)
     * @param otherMasterDetails how many master-detail fields the owner already has besides this one
     * @param maxPicklistValues the most values a picklist may have
     */
    record Context(Predicate<String> targetExists, String ownerApiName, boolean masterDetailAllowed,
            int otherMasterDetails, int maxPicklistValues) {
    }

    /**
     * The checked and completed form of a field's constraints and settings.
     *
     * @param required the constraint as stored
     * @param unique the constraint as stored
     * @param defaultValue the default as stored (trimmed; null when there is none)
     * @param configuration the settings of the type with every default applied
     */
    record Checked(boolean required, boolean unique, String defaultValue, FieldConfiguration configuration) {
    }

    /**
     * Checks a new field.
     *
     * @throws ApiException {@code VALIDATION_ERROR} listing every problem
     */
    static Checked check(FieldType type, boolean required, boolean unique, String defaultValue,
            FieldSettings settings, Context context) {
        Map<String, List<String>> problems = new LinkedHashMap<>();
        Checked checked = checkInto(problems, type, required, unique, defaultValue, settings, context);
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        return checked;
    }

    /**
     * Checks a changed field against what it was: the same rules as for a new one, plus what must stay (every value of
     * a picklist, the target of a reference).
     */
    static Checked checkChange(FieldType type, FieldConfiguration before, boolean required, boolean unique,
            String defaultValue, FieldSettings settings, Context context) {
        Map<String, List<String>> problems = new LinkedHashMap<>();
        Checked checked = checkInto(problems, type, required, unique, defaultValue, settings, context);
        if (problems.isEmpty()) {
            keepWhatMustStay(problems, before, checked.configuration());
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        return checked;
    }

    private static Checked checkInto(Map<String, List<String>> problems, FieldType type, boolean required,
            boolean unique, String defaultValue, FieldSettings given, Context context) {
        FieldSettings settings = given == null ? FieldSettings.none() : given;
        rejectUnusedSettings(problems, type, settings);
        FieldConfiguration configuration = configurationOf(problems, type, settings, context, required);

        boolean storedRequired = required;
        boolean storedUnique = unique;
        if (type == FieldType.MASTER_DETAIL) {
            storedRequired = true;
        } else if (required && !type.allowsRequired()) {
            add(problems, "required", "A field of this type cannot be required.");
            storedRequired = false;
        }
        if (type == FieldType.AUTO_NUMBER) {
            storedUnique = true;
        } else if (unique && !type.allowsUnique()) {
            add(problems, "unique", "A field of this type cannot be unique.");
            storedUnique = false;
        }
        String storedDefault = blankToNull(defaultValue);
        if (storedDefault != null) {
            if (!type.allowsDefault()) {
                add(problems, "defaultValue", "A field of this type cannot have a default value.");
                storedDefault = null;
            } else if (configuration != null) {
                String problem = defaultProblem(type, configuration, storedDefault);
                if (problem != null) {
                    add(problems, "defaultValue", problem);
                }
            }
        }
        if (storedUnique && storedDefault != null && type != FieldType.AUTO_NUMBER) {
            add(problems, "defaultValue", "A unique field cannot have a default value: every record would get the "
                    + "same one.");
        }
        return new Checked(storedRequired, storedUnique, storedDefault, configuration);
    }

    // ---- settings per type ----

    private static void rejectUnusedSettings(Map<String, List<String>> problems, FieldType type,
            FieldSettings settings) {
        Map<String, Object> given = new LinkedHashMap<>();
        given.put("maxLength", settings.maxLength());
        given.put("digits", settings.digits());
        given.put("precision", settings.precision());
        given.put("scale", settings.scale());
        given.put("values", settings.values());
        given.put("targetObject", settings.targetObject());
        given.put("expression", settings.expression());
        given.put("resultType", settings.resultType());
        given.put("prefix", settings.prefix());
        given.put("startAt", settings.startAt());
        given.put("width", settings.width());
        given.put("onDelete", settings.onDelete());
        given.put("reparentable", settings.reparentable());
        given.put("listLabel", settings.listLabel());
        given.forEach((name, value) -> {
            if (value != null && !type.settings().contains(name)) {
                add(problems, "settings." + name, "This setting is not used by a field of this type.");
            }
        });
    }

    private static FieldConfiguration configurationOf(Map<String, List<String>> problems, FieldType type,
            FieldSettings s, Context context, boolean required) {
        return switch (type) {
            case TEXT -> new TextConfiguration(pick(problems, "maxLength", s.maxLength(), TEXT_DEFAULT, 1,
                    TEXT_DEFAULT));
            case LONG_TEXT -> new TextConfiguration(pick(problems, "maxLength", s.maxLength(), LONG_TEXT_DEFAULT, 1,
                    LONG_TEXT_MAX));
            case EMAIL -> new TextConfiguration(EMAIL_LENGTH);
            case PHONE -> new TextConfiguration(PHONE_LENGTH);
            case URL -> new TextConfiguration(URL_LENGTH);
            case NUMBER -> new NumberConfiguration(pick(problems, "digits", s.digits(), NUMBER_DIGITS, 1,
                    NUMBER_DIGITS));
            case DECIMAL -> decimal(problems, s, 18, 2, 10);
            case CURRENCY -> decimal(problems, s, 18, 2, 6);
            case PERCENT -> decimal(problems, s, 8, 2, 6);
            case BOOLEAN, DATE, DATETIME, TIME -> new NoConfiguration();
            case PICKLIST, MULTI_PICKLIST -> picklist(problems, s, context);
            case LOOKUP, MASTER_DETAIL -> reference(problems, type, s, context, required);
            case FORMULA -> formula(problems, s);
            case AUTO_NUMBER -> autoNumber(problems, s);
        };
    }

    private static FieldConfiguration decimal(Map<String, List<String>> problems, FieldSettings s, int defaultPrecision,
            int defaultScale, int maxScale) {
        int precision = pick(problems, "precision", s.precision(), defaultPrecision, 1, PRECISION_MAX);
        int scale = pick(problems, "scale", s.scale(), Math.min(defaultScale, precision), 0, maxScale);
        if (scale > precision) {
            add(problems, "settings.scale", "Must not be more than the number of digits in all.");
        }
        return new DecimalConfiguration(precision, scale);
    }

    private static FieldConfiguration picklist(Map<String, List<String>> problems, FieldSettings s,
            Context context) {
        List<PicklistOption> options = s.values() == null ? List.of() : s.values();
        if (options.isEmpty()) {
            add(problems, "settings.values", "Give at least one value.");
            return new PicklistConfiguration(List.of());
        }
        if (options.size() > context.maxPicklistValues()) {
            add(problems, "settings.values", "A picklist can have at most " + context.maxPicklistValues()
                    + " values.");
        }
        List<PicklistValue> values = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PicklistOption option : options) {
            String value = option.value() == null ? "" : option.value().strip();
            String label = option.label() == null ? "" : option.label().strip();
            if (value.isEmpty() || value.length() > PICKLIST_VALUE_MAX || value.indexOf(';') >= 0
                    || hasControl(value)) {
                add(problems, "settings.values", "A value is 1 to 80 characters with no semicolon.");
                continue;
            }
            if (label.isEmpty() || label.length() > PICKLIST_VALUE_MAX || hasControl(label)) {
                add(problems, "settings.values", "A label is 1 to 80 characters.");
                continue;
            }
            if (!seen.add(value.toLowerCase(Locale.ROOT))) {
                add(problems, "settings.values", "Two values are the same (upper and lower case count as the same).");
                continue;
            }
            values.add(new PicklistValue(value, label, option.active() == null || option.active()));
        }
        return new PicklistConfiguration(values);
    }

    private static FieldConfiguration reference(Map<String, List<String>> problems, FieldType type, FieldSettings s,
            Context context, boolean required) {
        String target = s.targetObject() == null ? "" : s.targetObject().strip();
        if (target.isEmpty()) {
            add(problems, "settings.targetObject", "Choose the object the field points to.");
            return ReferenceConfiguration.defaultsFor(type, "");
        }
        if (!context.targetExists().test(target)) {
            add(problems, "settings.targetObject", "This object does not exist.");
        } else if (type == FieldType.MASTER_DETAIL && target.equals(context.ownerApiName())) {
            add(problems, "settings.targetObject", "A master-detail field cannot point to its own object.");
        }
        if (type == FieldType.MASTER_DETAIL) {
            if (!context.masterDetailAllowed()) {
                add(problems, "type", "A master-detail field can only be added to a custom object.");
            } else if (context.otherMasterDetails() >= MASTER_DETAIL_PER_OBJECT) {
                add(problems, "type", "An object can have at most " + MASTER_DETAIL_PER_OBJECT
                        + " master-detail fields.");
            }
        }
        DeleteBehaviour onDelete = deleteBehaviour(problems, type, s.onDelete(), required);
        return new ReferenceConfiguration(target, onDelete, Boolean.TRUE.equals(s.reparentable()),
                listLabel(problems, s.listLabel()));
    }

    /**
     * What happens when the record a lookup points at is removed (ADR-0063). A master-detail always removes its
     * details; a lookup clears its pointer, or refuses the removal. A required lookup cannot clear a value it must
     * have, so it refuses when nothing is chosen, and choosing to clear is a mistake that is reported.
     */
    private static DeleteBehaviour deleteBehaviour(Map<String, List<String>> problems, FieldType type, String given,
            boolean required) {
        if (type == FieldType.MASTER_DETAIL) {
            return DeleteBehaviour.CASCADE;
        }
        String text = given == null ? "" : given.strip();
        if (text.isEmpty()) {
            return required ? DeleteBehaviour.REFUSE : DeleteBehaviour.CLEAR;
        }
        DeleteBehaviour chosen;
        if (text.equals(DeleteBehaviour.CLEAR.name())) {
            chosen = DeleteBehaviour.CLEAR;
        } else if (text.equals(DeleteBehaviour.REFUSE.name())) {
            chosen = DeleteBehaviour.REFUSE;
        } else {
            add(problems, "settings.onDelete", "Choose CLEAR (empty the link) or REFUSE (do not remove the other "
                    + "record while records point at it).");
            return required ? DeleteBehaviour.REFUSE : DeleteBehaviour.CLEAR;
        }
        if (required && chosen == DeleteBehaviour.CLEAR) {
            add(problems, "settings.onDelete", "A required lookup cannot empty its link: choose REFUSE.");
        }
        return chosen;
    }

    private static String listLabel(Map<String, List<String>> problems, String given) {
        String label = given == null ? "" : given.strip();
        if (label.length() > PICKLIST_VALUE_MAX || hasControl(label)) {
            add(problems, "settings.listLabel", "A list label is up to " + PICKLIST_VALUE_MAX + " characters.");
            return "";
        }
        return label;
    }

    private static FieldConfiguration formula(Map<String, List<String>> problems, FieldSettings s) {
        String expression = s.expression() == null ? "" : s.expression().strip();
        if (expression.isEmpty() || expression.length() > FORMULA_MAX) {
            add(problems, "settings.expression", "Write the formula (up to " + FORMULA_MAX + " characters).");
        } else if (hasControlExceptLayout(expression)) {
            add(problems, "settings.expression", "A formula contains no control characters.");
        }
        FieldType result = FieldType.fromCode(s.resultType()).filter(FORMULA_RESULTS::contains).orElse(null);
        if (result == null) {
            add(problems, "settings.resultType", "Choose what the formula yields: text, number, decimal, currency, "
                    + "percent, checkbox, date, date and time or time.");
            result = FieldType.TEXT;
        }
        return new FormulaConfiguration(expression, result);
    }

    private static FieldConfiguration autoNumber(Map<String, List<String>> problems, FieldSettings s) {
        String prefix = s.prefix() == null ? "" : s.prefix().strip();
        if (!PREFIX.matcher(prefix).matches()) {
            add(problems, "settings.prefix", "Up to " + AUTO_NUMBER_PREFIX_MAX
                    + " letters, digits, hyphens or underscores.");
        }
        long startAt = s.startAt() == null ? 1 : s.startAt();
        if (startAt < 0 || startAt > AUTO_NUMBER_START_MAX) {
            add(problems, "settings.startAt", "Must be between 0 and " + AUTO_NUMBER_START_MAX + ".");
            startAt = 1;
        }
        int width = pick(problems, "width", s.width(), AUTO_NUMBER_WIDTH_DEFAULT, 1, AUTO_NUMBER_WIDTH_MAX);
        return new AutoNumberConfiguration(prefix, startAt, width);
    }

    private static int pick(Map<String, List<String>> problems, String name, Integer value, int fallback, int min,
            int max) {
        if (value == null) {
            return fallback;
        }
        if (value < min || value > max) {
            add(problems, "settings." + name, "Must be between " + min + " and " + max + ".");
            return fallback;
        }
        return value;
    }

    // ---- default values ----

    /** Why the text cannot be the default value of such a field, or null when it fits. */
    private static String defaultProblem(FieldType type, FieldConfiguration configuration, String text) {
        switch (type) {
            case TEXT, LONG_TEXT -> {
                int max = ((TextConfiguration) configuration).maxLength();
                return text.length() > max ? "Is longer than the " + max + " characters this field allows." : null;
            }
            case EMAIL -> {
                return EMAIL.matcher(text).matches() && text.length() <= EMAIL_LENGTH ? null
                        : "Must be an e-mail address.";
            }
            case PHONE -> {
                return PHONE.matcher(text).matches() ? null : "Must be a telephone number.";
            }
            case URL -> {
                return URL.matcher(text).matches() && text.length() <= URL_LENGTH ? null
                        : "Must be a web address starting with http:// or https://.";
            }
            case NUMBER -> {
                return numberProblem(text, ((NumberConfiguration) configuration).digits());
            }
            case DECIMAL, CURRENCY, PERCENT -> {
                DecimalConfiguration decimal = (DecimalConfiguration) configuration;
                return decimalProblem(text, decimal.precision(), decimal.scale());
            }
            case BOOLEAN -> {
                return text.equals("true") || text.equals("false") ? null : "Must be true or false.";
            }
            case DATE -> {
                return parses(() -> LocalDate.parse(text)) ? null : "Must be a date written like 2030-01-31.";
            }
            case DATETIME -> {
                return parses(() -> Instant.parse(text)) ? null
                        : "Must be a date and time in UTC written like 2030-01-31T09:30:00Z.";
            }
            case TIME -> {
                return parses(() -> LocalTime.parse(text)) ? null : "Must be a time written like 09:30 or 09:30:15.";
            }
            case PICKLIST -> {
                return activeValue(((PicklistConfiguration) configuration).values(), text) ? null
                        : "Must be one of the active values of the list.";
            }
            case MULTI_PICKLIST -> {
                List<PicklistValue> values = ((PicklistConfiguration) configuration).values();
                Set<String> chosen = new HashSet<>();
                for (String part : text.split(";", -1)) {
                    if (!activeValue(values, part) || !chosen.add(part)) {
                        return "Must be active values of the list, each once, separated by semicolons.";
                    }
                }
                return null;
            }
            default -> {
                return "A field of this type cannot have a default value.";
            }
        }
    }

    private static String numberProblem(String text, int digits) {
        try {
            BigDecimal number = new BigDecimal(text);
            if (number.scale() > 0 && number.stripTrailingZeros().scale() > 0) {
                return "Must be a whole number.";
            }
            return number.abs().toBigInteger().toString().length() <= digits ? null
                    : "Has more than the " + digits + " digits this field allows.";
        } catch (NumberFormatException e) {
            return "Must be a whole number.";
        }
    }

    private static String decimalProblem(String text, int precision, int scale) {
        try {
            BigDecimal number = new BigDecimal(text).stripTrailingZeros();
            int fraction = Math.max(number.scale(), 0);
            int whole = Math.max(number.precision() - number.scale(), 1);
            if (fraction > scale) {
                return "Has more than " + scale + " digits after the point.";
            }
            return whole + scale <= precision ? null : "Is too large for the " + precision + " digits this field "
                    + "allows.";
        } catch (NumberFormatException e) {
            return "Must be a number.";
        }
    }

    private static boolean activeValue(List<PicklistValue> values, String text) {
        return values.stream().anyMatch(value -> value.active() && value.value().equals(text));
    }

    private static boolean parses(Runnable parse) {
        try {
            parse.run();
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    // ---- what must stay when a field changes ----

    private static void keepWhatMustStay(Map<String, List<String>> problems, FieldConfiguration before,
            FieldConfiguration after) {
        if (before instanceof PicklistConfiguration was && after instanceof PicklistConfiguration now) {
            Set<String> kept = new HashSet<>();
            now.values().forEach(value -> kept.add(value.value()));
            for (PicklistValue value : was.values()) {
                if (!kept.contains(value.value())) {
                    add(problems, "settings.values", "A value cannot be removed once it exists: switch it off "
                            + "instead.");
                    break;
                }
            }
        }
        if (before instanceof ReferenceConfiguration was && after instanceof ReferenceConfiguration now
                && !was.targetObject().equals(now.targetObject())) {
            add(problems, "settings.targetObject", "The object a field points to cannot be changed.");
        }
    }

    // ---- stored form to API form ----

    /** The settings of the configuration as the API shows them, only those the type of the field takes. */
    static FieldSettings settingsOf(FieldType type, FieldConfiguration configuration) {
        if (configuration instanceof ReferenceConfiguration reference) {
            return FieldSettings.reference(reference.targetObject(),
                    type == FieldType.LOOKUP ? reference.onDelete().name() : null,
                    type == FieldType.MASTER_DETAIL ? reference.reparentable() : null, reference.listLabel());
        }
        return settingsOf(configuration);
    }

    /** The settings of the configuration as the API shows them. */
    static FieldSettings settingsOf(FieldConfiguration configuration) {
        return switch (configuration) {
            case TextConfiguration text -> new FieldSettings(text.maxLength(), null, null, null, null, null, null,
                    null, null, null, null);
            case NumberConfiguration number -> new FieldSettings(null, number.digits(), null, null, null, null, null,
                    null, null, null, null);
            case DecimalConfiguration decimal -> new FieldSettings(null, null, decimal.precision(), decimal.scale(),
                    null, null, null, null, null, null, null);
            case NoConfiguration none -> FieldSettings.none();
            case PicklistConfiguration picklist -> new FieldSettings(null, null, null, null,
                    picklist.values().stream().map(v -> new PicklistOption(v.value(), v.label(), v.active()))
                            .toList(), null, null, null, null, null, null);
            case ReferenceConfiguration reference -> FieldSettings.reference(reference.targetObject(),
                    reference.onDelete().name(), reference.reparentable(), reference.listLabel());
            case FormulaConfiguration formula -> new FieldSettings(null, null, null, null, null, null,
                    formula.expression(), formula.resultType().name(), null, null, null);
            case AutoNumberConfiguration auto -> new FieldSettings(null, null, null, null, null, null, null, null,
                    auto.prefix(), auto.startAt(), auto.width());
        };
    }

    private static String blankToNull(String text) {
        if (text == null) {
            return null;
        }
        String stripped = text.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static boolean hasControl(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasControlExceptLayout(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\t' && c != '\r') {
                return true;
            }
        }
        return false;
    }

    private static void add(Map<String, List<String>> problems, String field, String problem) {
        problems.computeIfAbsent(field, key -> new ArrayList<>());
        if (!problems.get(field).contains(problem)) {
            problems.get(field).add(problem);
        }
    }
}
