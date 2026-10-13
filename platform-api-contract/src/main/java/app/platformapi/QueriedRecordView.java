package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * One record of the answer to a record query (Sprint 16).
 *
 * @param record the record, with only the fields asked for that the caller may read
 * @param linked what was brought of the records its links point at, by the API name of the link field; only the links
 *        the query asked to follow
 */
public record QueriedRecordView(@NotNull RecordView record, @NotNull Map<String, LinkedRecordView> linked) {

    /** Copies the map. */
    public QueriedRecordView {
        linked = Map.copyOf(linked);
    }

    @Override
    public String toString() {
        return "QueriedRecordView[redacted]";
    }
}
