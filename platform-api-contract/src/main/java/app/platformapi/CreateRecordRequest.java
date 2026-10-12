package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Creates a record of an object (Sprint 14, Sprint 15). The values are typed by a person, so the record never prints.
 *
 * <p>Every key must be a field of the object that the caller may edit and that people can write (not a system,
 * calculated or retired field). A value is a text, a number, true or false, or a list of those, and the whole record is
 * bounded in size. The write then goes through the pipeline in this order: the record type, the field permission, the
 * type of each value, the required fields, the linked records, the validation rules, the formulas. The first stage that
 * finds problems answers with all of its problems, each with its place; nothing is saved.
 *
 * @param fields the values by field API name; a key with no value (null) is the same as leaving it out
 * @param recordType the API name of the record type of the new record; left out, the object's default record type is
 *        used if it has one
 */
public record CreateRecordRequest(@NotNull Map<String, Object> fields, String recordType) {

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
