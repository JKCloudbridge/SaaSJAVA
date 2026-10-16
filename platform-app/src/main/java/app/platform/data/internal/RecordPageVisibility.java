package app.platform.data.internal;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Whether a component or an action of a record page is shown (Sprint 20, ADR-0131, the closed rule of Sprint 12,
 * ADR-0069): a field compared with a value, or an ability, combined with {@code all}/{@code any} nested once. The
 * rule is read straight off the record page's own JSON ({@link app.platform.metadata.RecordPage#definition()}): never
 * text that is evaluated, no expression and no code from an organization.
 *
 * <p>A field the member may not read is absent from the record's values, exactly as on every other read: it is never
 * fetched to decide a rule. A rule about such a field answers {@code IS_EMPTY} true and everything else false, the
 * same as a field with no value. A record that does not exist yet (a create form) has no values at all: a field rule
 * is permissive (true) until there is something to test, so a tab a new record would otherwise never show is not
 * hidden before the person has had a chance to fill it in.
 */
final class RecordPageVisibility {

    private RecordPageVisibility() {
    }

    /**
     * @param hasAbility whether the caller holds the ability named
     * @param record the values of the record the rule judges, or null for one that does not exist yet
     */
    static boolean visible(Object rule, Predicate<String> hasAbility, Map<String, Object> record) {
        if (!(rule instanceof Map<?, ?> map)) {
            return true;
        }
        if (map.get("ability") instanceof String ability) {
            return hasAbility.test(ability);
        }
        if (map.get("all") instanceof List<?> all) {
            return all.stream().allMatch(inner -> visible(inner, hasAbility, record));
        }
        if (map.get("any") instanceof List<?> any) {
            return any.stream().anyMatch(inner -> visible(inner, hasAbility, record));
        }
        if (map.get("field") instanceof String field && map.get("operator") instanceof String operator) {
            return field(field, operator, map.get("value"), record);
        }
        return true;
    }

    private static boolean field(String field, String operator, Object expected, Map<String, Object> record) {
        if (record == null) {
            return true;
        }
        Object actual = record.get(field);
        return switch (operator) {
            case "IS_EMPTY" -> isEmpty(actual);
            case "IS_NOT_EMPTY" -> !isEmpty(actual);
            case "EQUALS" -> !isEmpty(actual) && equal(actual, expected);
            case "NOT_EQUALS" -> isEmpty(actual) || !equal(actual, expected);
            default -> true;
        };
    }

    private static boolean isEmpty(Object value) {
        return value == null || (value instanceof String text && text.isEmpty());
    }

    private static boolean equal(Object actual, Object expected) {
        if (actual instanceof Number left && expected instanceof Number right) {
            return left.doubleValue() == right.doubleValue();
        }
        return Objects.equals(String.valueOf(actual), String.valueOf(expected));
    }
}
