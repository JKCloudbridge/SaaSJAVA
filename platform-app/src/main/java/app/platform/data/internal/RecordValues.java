package app.platform.data.internal;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.ObjectDefinition;
import app.platformapi.ApiException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a write may contain in Sprint 14 (ADR-0089): every key is a field of the object that people may write, and every
 * value has a shape the store can keep safely and a size it can bear. Nothing here knows what a value <em>means</em>:
 * whether a text is an e-mail address, a number fits its field, a required field has a value, a lookup points at a
 * record, a rule is met or a formula is right is Sprint 15, which adds those checks to the write path in the documented
 * order.
 *
 * <p>The shapes: a text (no NUL character, which the database cannot store, and bounded), a number (kept exactly, as a
 * decimal of at most 38 digits), true or false, or a list of those. Objects and lists inside lists are refused. The
 * result is a copy in which every number is a decimal, so the stored text does not depend on how the number arrived.
 *
 * <p>Errors name the field key (a key that is not shaped like an API name is not repeated: it is typed text) and never
 * a value.
 */
final class RecordValues {

    /** The most characters of one text value. */
    static final int MAX_TEXT_LENGTH = 100_000;

    /** The most elements of one list value. */
    static final int MAX_LIST_SIZE = 1_000;

    private static final int MAX_DIGITS = 38;
    private static final Pattern KEY_SHAPE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,59}");
    private static final String PROBLEMS_KEY = "fields";

    /**
     * A write that passed.
     *
     * @param set the values to store, numbers as decimals
     * @param clear the fields that lose their value
     */
    record Checked(Map<String, Object> set, List<String> clear) {

        Checked {
            set = Map.copyOf(set);
            clear = List.copyOf(clear);
        }

        /** The names of every field the write touches, in a stable order. */
        List<String> names() {
            Set<String> all = new LinkedHashSet<>(set.keySet().stream().sorted().toList());
            all.addAll(clear.stream().sorted().toList());
            return List.copyOf(all);
        }
    }

    private final RecordJson json;
    private final int maxRecordBytes;

    RecordValues(RecordJson json, int maxRecordBytes) {
        this.json = json;
        this.maxRecordBytes = maxRecordBytes;
    }

    /**
     * Checks a write against the object.
     *
     * @param object the object the record belongs to
     * @param set values by field API name
     * @param clear fields to empty
     * @param requireSomething whether a write that changes nothing is refused (an update is, a create is not)
     * @throws ApiException {@code VALIDATION_ERROR} listing every problem
     */
    Checked check(ObjectDefinition object, Map<String, Object> set, List<String> clear, boolean requireSomething) {
        Map<String, List<String>> problems = new LinkedHashMap<>();
        Map<String, Object> accepted = new LinkedHashMap<>();
        List<String> cleared = new ArrayList<>();
        for (Map.Entry<String, Object> entry : set.entrySet()) {
            String key = entry.getKey();
            if (writable(object, key, problems)) {
                Object value = normalised(entry.getValue(), problemKey(key), problems);
                if (value != null) {
                    accepted.put(key, value);
                }
            }
        }
        for (String key : clear) {
            if (set.containsKey(key)) {
                add(problems, problemKey(key), "This field is both given a value and cleared.");
            } else if (writable(object, key, problems)) {
                cleared.add(key);
            }
        }
        if (requireSomething && set.isEmpty() && clear.isEmpty()) {
            add(problems, PROBLEMS_KEY, "Give at least one field to change.");
        }
        if (problems.isEmpty()) {
            int bytes = json.write(accepted).getBytes(StandardCharsets.UTF_8).length;
            if (bytes > maxRecordBytes) {
                add(problems, PROBLEMS_KEY, "The values are too large for one record.");
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        return new Checked(accepted, cleared);
    }

    private static boolean writable(ObjectDefinition object, String key, Map<String, List<String>> problems) {
        if (!KEY_SHAPE.matcher(key).matches()) {
            add(problems, PROBLEMS_KEY, "A key is not the name of a field.");
            return false;
        }
        FieldDefinition field = object.field(key).orElse(null);
        if (field == null) {
            add(problems, key, "This is not a field of the object.");
            return false;
        }
        if (field.kind() == DefinitionKind.SYSTEM || field.type().calculated() || field.retired()) {
            add(problems, key, "This field cannot be written.");
            return false;
        }
        return true;
    }

    private static String problemKey(String key) {
        return KEY_SHAPE.matcher(key).matches() ? key : PROBLEMS_KEY;
    }

    /** The value in its stored shape, or null (after noting the problem) when it cannot be stored. */
    private static Object normalised(Object value, String key, Map<String, List<String>> problems) {
        if (value instanceof List<?> list) {
            if (list.size() > MAX_LIST_SIZE) {
                add(problems, key, "A list has too many values.");
                return null;
            }
            List<Object> kept = new ArrayList<>();
            for (Object element : list) {
                Object one = element instanceof List<?> || element instanceof Map<?, ?> ? null : scalar(element);
                if (one == null) {
                    add(problems, key, "A list holds only texts, numbers and true or false.");
                    return null;
                }
                kept.add(one);
            }
            return List.copyOf(kept);
        }
        Object one = value instanceof Map<?, ?> ? null : scalar(value);
        if (one == null) {
            add(problems, key, "The value must be a text, a number, true or false, or a list of those.");
        }
        return one;
    }

    private static Object scalar(Object value) {
        if (value instanceof String text) {
            return text.length() <= MAX_TEXT_LENGTH && text.indexOf('\u0000') < 0 ? text : null;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return decimal(number);
        }
        return null;
    }

    private static BigDecimal decimal(Number number) {
        BigDecimal decimal;
        if (number instanceof BigDecimal exact) {
            decimal = exact;
        } else if (number instanceof BigInteger big) {
            decimal = new BigDecimal(big);
        } else if (number instanceof Double || number instanceof Float) {
            double d = number.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return null;
            }
            decimal = new BigDecimal(Double.toString(d));
        } else {
            decimal = BigDecimal.valueOf(number.longValue());
        }
        boolean inRange = decimal.signum() == 0
                || (decimal.precision() <= MAX_DIGITS && Math.abs(decimal.scale()) <= MAX_DIGITS);
        return inRange ? decimal : null;
    }

    private static void add(Map<String, List<String>> problems, String key, String text) {
        List<String> texts = problems.computeIfAbsent(key, name -> new ArrayList<>());
        if (!texts.contains(text)) {
            texts.add(text);
        }
    }
}
