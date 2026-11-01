package app.platform.publicapi;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One record of a bulk call (Sprint 30, ADR-0245). What is needed depends on the operation of the call.
 *
 * @param id the record (UPDATE and DELETE)
 * @param version the version the change is based on (UPDATE)
 * @param recordType the record type to create the record with, or to move it to
 * @param fields the values by field API name; a null value clears the field in an UPDATE and is no value in a CREATE
 * @param clear the API names of fields that lose their value (UPDATE)
 */
@Schema(description = "One record of a bulk call. CREATE needs `fields` (and no `id`); UPDATE needs `id`, `version` "
        + "and the `fields` to set or `clear`; DELETE needs `id`.")
public record PublicBulkItem(
        @Schema(description = "The record (UPDATE and DELETE).") UUID id,
        @Schema(description = "The version the change is based on (UPDATE).", example = "3") Long version,
        @Size(max = 80) @Schema(description = "The record type to create the record with, or to move it to.")
        String recordType,
        @Schema(description = "The values by field API name. A null value clears the field in an UPDATE.")
        Map<String, Object> fields,
        @Size(max = 500) @Schema(description = "API names of fields that lose their value (UPDATE).")
        List<String> clear) {

    /** Copies what is given, so that it cannot be changed from outside; a null value clears its field. */
    public PublicBulkItem {
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

    /** Prints nothing of the values. */
    @Override
    public String toString() {
        return "PublicBulkItem[redacted]";
    }
}
