package app.platformapi;

import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One record of a bulk call (Sprint 17). What is needed depends on the operation of the call: to create, the values
 * and optionally the record type; to change, the identifier, the version that was read, and the values to set or clear;
 * to delete, the identifier. The values are typed by a person, so the item never prints.
 *
 * @param id the record (change and delete)
 * @param version the version the change is based on (change)
 * @param recordType the record type to create the record with, or to move it to
 * @param fields the values by field API name; a null value clears the field in a change and is no value in a create
 * @param clear the API names of fields that lose their value (change)
 */
public record BulkRecordItem(UUID id, Long version, @Size(max = 60) String recordType, Map<String, Object> fields,
        List<String> clear) {

    /** Copies what is given, so that it cannot be changed from outside; a null value clears its field. */
    public BulkRecordItem {
        List<String> cleared = new ArrayList<>(clear == null ? List.of() : clear);
        Map<String, Object> kept = new HashMap<>(fields == null ? Map.of() : fields);
        kept.entrySet().removeIf(entry -> {
            if (entry.getValue() == null) {
                cleared.add(entry.getKey());
                return true;
            }
            return false;
        });
        clear = List.copyOf(cleared.stream().filter(Objects::nonNull).distinct().toList());
        fields = Map.copyOf(kept);
    }

    @Override
    public String toString() {
        return "BulkRecordItem[redacted]";
    }
}
