package app.platform.publicapi;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Changes some values of a record (Sprint 30, ADR-0245). The version the change is based on travels in the
 * {@code If-Match} header, not in the body. A property that is not listed here is refused, with its place.
 *
 * @param fields the new values by field API name; a null value clears the field
 * @param clear the API names of fields that lose their value
 * @param recordType the API name of the record type to move the record to; left out, the record keeps its type
 */
@Schema(description = "A merge: only the fields named change, the others keep their value. A null value, or a name "
        + "in `clear`, removes a value. The version the change is based on is sent in the If-Match header.")
public record PublicUpdateRequest(
        @Schema(description = "The new values by field API name. A null value clears the field.",
                example = "{\"Salary__c\": 1300}") Map<String, Object> fields,
        @Size(max = 500) @Schema(description = "API names of fields that lose their value.") List<String> clear,
        @Size(max = 80) @Schema(description = "The API name of the record type to move the record to.")
        String recordType) {

    /** Copies what is given, so that it cannot be changed from outside; a null value clears its field. */
    public PublicUpdateRequest {
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

    /** Prints nothing of the values. */
    @Override
    public String toString() {
        return "PublicUpdateRequest[redacted]";
    }
}
