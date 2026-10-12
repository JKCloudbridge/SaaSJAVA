package app.platform.data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One record of an object, as the record store keeps it (Sprint 14, ADR-0084). The values are text, numbers, booleans
 * or lists of those that people typed, so the record never prints them (ADR-0012).
 *
 * @param id the identifier: opaque, time-ordered (version 7), never a count; unique across organizations, but never a
 *        proof of anything: the organization always comes from the host
 * @param objectApiName the object it belongs to; never changes
 * @param recordType the API name of its record type, or null (checked on every write since Sprint 15)
 * @param ownerId the user who owns it (Sprint 17 reads it for record-level security)
 * @param fields the values by field API name; a field without a value is not here
 * @param version raised by one on every change; a change must carry the version it was based on
 * @param createdAt when it was created
 * @param createdBy who created it
 * @param updatedAt when it was last changed (a move to or from the recycle bin is a change)
 * @param updatedBy who changed it last
 * @param deletedAt when it was moved to the recycle bin, or null for a live record
 * @param deletedBy who moved it there, or null
 */
public record DataRecord(UUID id, String objectApiName, String recordType, UUID ownerId, Map<String, Object> fields,
        long version, Instant createdAt, UUID createdBy, Instant updatedAt, UUID updatedBy, Instant deletedAt,
        UUID deletedBy) {

    /** Copies the values, so that they cannot be changed from outside. */
    public DataRecord {
        fields = Map.copyOf(fields);
    }

    /** The same record with other values (the values a member may see, Sprint 15). */
    public DataRecord withFields(Map<String, Object> other) {
        return new DataRecord(id, objectApiName, recordType, ownerId, other, version, createdAt, createdBy, updatedAt,
                updatedBy, deletedAt, deletedBy);
    }

    /** Whether the record is in the recycle bin. */
    public boolean deleted() {
        return deletedAt != null;
    }

    /** Prints the identity only: the values are typed text (ADR-0012). */
    @Override
    public String toString() {
        return "DataRecord[" + objectApiName + " " + id + " v" + version + "]";
    }
}
