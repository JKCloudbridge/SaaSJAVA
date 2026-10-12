package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Creates a record of an object (Sprint 14). The values are typed by a person, so the record never prints.
 *
 * <p>This is the first, narrow form of the endpoint: every key must be a field of the object that people can write
 * (not a system, calculated or retired field), a value is a text, a number, true or false, or a list of those, and the
 * whole record is bounded in size. Nothing yet checks a value against the type of its field, whether a required field
 * has a value, a lookup points at a record, or a validation rule is met: Sprint 15 adds those, in the documented order.
 *
 * @param fields the values by field API name; a key with no value (null) is the same as leaving it out
 */
public record CreateRecordRequest(@NotNull Map<String, Object> fields) {

    /** Copies the values, so that they cannot be changed from outside; a null value is no value. */
    public CreateRecordRequest {
        fields = fields == null ? Map.of() : Map.copyOf(withoutNulls(fields));
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> source) {
        Map<String, Object> copy = new HashMap<>(source);
        copy.values().removeIf(Objects::isNull);
        return copy;
    }

    @Override
    public String toString() {
        return "CreateRecordRequest[redacted]";
    }
}
