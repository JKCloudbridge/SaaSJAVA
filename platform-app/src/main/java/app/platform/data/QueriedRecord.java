package app.platform.data;

import java.util.Map;

/**
 * One record of the answer to a query, with the linked records the query asked for (by the API name of the link field).
 *
 * @param record the record, with only the fields asked for that the member may read
 * @param linked what was brought of the records its links point at
 */
public record QueriedRecord(DataRecord record, Map<String, LinkedRecord> linked) {

    /** Copies the map. */
    public QueriedRecord {
        linked = Map.copyOf(linked);
    }
}
