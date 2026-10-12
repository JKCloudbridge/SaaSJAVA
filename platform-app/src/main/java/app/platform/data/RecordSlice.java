package app.platform.data;

import java.util.List;

/**
 * One page of the records of an object, in identifier order (Sprint 14).
 *
 * @param records the records of the page
 * @param hasMore whether records follow the last one; the next page starts after its identifier
 */
public record RecordSlice(List<DataRecord> records, boolean hasMore) {

    /** Copies the list. */
    public RecordSlice {
        records = List.copyOf(records);
    }
}
