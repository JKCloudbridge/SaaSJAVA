package app.platformapi;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Sample values for trying a stored validation rule or formula field (Sprint 13). Nothing is stored and nothing is
 * logged. The values are typed by a person, so the record never prints.
 *
 * @param values sample values by field API name, each written as text for the type of its field (a number in plain
 *        digits, true or false, a date like 2030-01-31); a field left out or blank has no value
 */
public record TestValuesRequest(Map<String, String> values) {

    /** Copies the values, so that they cannot be changed from outside. */
    public TestValuesRequest {
        values = values == null ? Map.of() : Map.copyOf(withoutNulls(values));
    }

    /** A blank sample value may arrive as a null; it means "no value", which a missing key says as well. */
    private static Map<String, String> withoutNulls(Map<String, String> source) {
        Map<String, String> copy = new HashMap<>(source);
        copy.values().removeIf(Objects::isNull);
        return copy;
    }

    @Override
    public String toString() {
        return "TestValuesRequest[redacted]";
    }
}
