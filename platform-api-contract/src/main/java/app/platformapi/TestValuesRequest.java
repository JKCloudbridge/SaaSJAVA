package app.platformapi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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
        values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    @Override
    public String toString() {
        return "TestValuesRequest[redacted]";
    }
}
