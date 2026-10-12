package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Changes some values of a record (Sprint 14, Sprint 15). The values are typed by a person, so the record never prints.
 *
 * <p>Only the fields named here change; the others keep their value. A field whose value is null, or that is listed
 * in {@code clear}, loses its value. The request carries the version that was read: if the record has changed since,
 * nothing is changed and the answer is {@code CONCURRENT_MODIFICATION}. The checks are those of
 * {@link CreateRecordRequest}, and they judge the record as it will be after the change, so clearing a required field
 * is refused.
 *
 * @param version the version of the record that the values are based on
 * @param fields the new values by field API name; a null value clears the field
 * @param clear the API names of fields that lose their value
 * @param recordType the API name of the record type to move the record to; left out, the record keeps its type
 */
public record UpdateRecordRequest(@NotNull Long version, Map<String, Object> fields, List<String> clear,
        String recordType) {

    /** Copies what is given, so that it cannot be changed from outside; a null value clears its field. */
    public UpdateRecordRequest {
        List<String> cleared = new ArrayList<>(clear == null ? List.of() : clear);
        if (fields != null) {
            fields.forEach((name, value) -> {
                if (value == null) {
                    cleared.add(name);
                }
            });
        }
        clear = List.copyOf(cleared.stream().filter(Objects::nonNull).distinct().toList());
        fields = fields == null ? Map.of() : Map.copyOf(withoutNulls(fields));
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> source) {
        Map<String, Object> copy = new HashMap<>(source);
        copy.values().removeIf(Objects::isNull);
        return copy;
    }

    @Override
    public String toString() {
        return "UpdateRecordRequest[redacted]";
    }
}
