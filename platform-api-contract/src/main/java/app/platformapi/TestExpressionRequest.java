package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tries an expression that is not stored yet on sample values (Sprint 13). Nothing is stored and nothing is logged.
 * The text and the values are typed by a person, so the record never prints.
 *
 * @param objectApiName the object whose fields the expression may use
 * @param expression the expression text
 * @param purpose {@code FORMULA} or {@code VALIDATION_RULE}
 * @param values sample values by field API name, each written as text for the type of its field (a number in plain
 *        digits, true or false, a date like 2030-01-31); a field left out or blank has no value
 */
public record TestExpressionRequest(@NotBlank @Size(max = 60) String objectApiName,
        @NotBlank @Size(max = 4000) String expression, @NotBlank @Size(max = 20) String purpose,
        Map<String, String> values) {

    /** Copies the values, so that they cannot be changed from outside. */
    public TestExpressionRequest {
        values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    @Override
    public String toString() {
        return "TestExpressionRequest[redacted]";
    }
}
