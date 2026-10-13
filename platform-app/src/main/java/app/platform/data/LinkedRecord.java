package app.platform.data;

import java.util.Map;
import java.util.UUID;

/**
 * What a query brings of the record a link points at (Sprint 16, ADR-0107).
 *
 * @param id the identifier the link holds
 * @param fields the fields asked for that the member may read; empty when the link points at nothing, at a record in
 *        the recycle bin, at an object the member may not read or at a platform-owned object (all alike)
 */
public record LinkedRecord(UUID id, Map<String, Object> fields) {

    /** Copies the values. */
    public LinkedRecord {
        fields = Map.copyOf(fields);
    }

    /** Prints the identifier only. */
    @Override
    public String toString() {
        return "LinkedRecord[" + id + "]";
    }
}
