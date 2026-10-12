package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;

/**
 * One record of an object (Sprint 14). The values are what people typed, so the record never prints.
 *
 * @param id the identifier: opaque, time-ordered, never a count
 * @param objectApiName the object it belongs to
 * @param recordType the API name of its record type; left out when the record has none
 * @param version the version to send back when changing it
 * @param ownerId the user who owns it (the person who created it)
 * @param fields the values by field API name; a field without a value is left out, and so is a field the caller may
 *        not read (it is absent, not masked)
 * @param createdAt when it was created
 * @param updatedAt when it was last changed
 * @param deletedAt when it was moved to the recycle bin, or null for a live record
 */
public record RecordView(@NotNull String id, @NotNull String objectApiName, String recordType, @NotNull Long version,
        @NotNull String ownerId, @NotNull Map<String, Object> fields, @NotNull Instant createdAt,
        @NotNull Instant updatedAt, Instant deletedAt) {

    /** Copies the values, so that they cannot be changed from outside. */
    public RecordView {
        fields = Map.copyOf(fields);
    }

    @Override
    public String toString() {
        return "RecordView[redacted]";
    }
}
