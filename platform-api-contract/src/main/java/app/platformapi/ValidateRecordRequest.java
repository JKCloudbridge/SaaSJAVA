package app.platformapi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Asks what the server would say to a save, without saving (Sprint 19, ADR-0129, ADR-0079). The values are typed by a
 * person, so the request never prints.
 *
 * <p>It is the write of {@link CreateRecordRequest} (no {@code recordId}) or of {@link UpdateRecordRequest} (a
 * {@code recordId}) run through every stage of the write pipeline and then undone: nothing is stored, no number is
 * used up, no audit record or event is written. The checks, their order and their words are the ones a real save
 * would meet, because they are the same code.
 *
 * @param recordId the record being changed, or null for a record that would be created
 * @param recordType the record type to give the record; left out, a new record gets the member's default type and an
 *        existing one keeps its type
 * @param fields the values by field API name; a null value clears the field
 * @param clear the API names of fields that lose their value
 */
public record ValidateRecordRequest(UUID recordId, String recordType, Map<String, Object> fields,
        List<String> clear) {

    /** Copies what is given, so that it cannot be changed from outside; a null value clears its field. */
    public ValidateRecordRequest {
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
        return "ValidateRecordRequest[redacted]";
    }
}
