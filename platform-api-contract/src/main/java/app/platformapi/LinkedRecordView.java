package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * What a record query brought of the record a link points at (Sprint 16).
 *
 * @param id the identifier the link holds
 * @param fields the fields asked for that the caller may read on the linked object; absent keys are fields the caller
 *        may not read or that have no value. Empty when the link points at nothing the caller can see, whatever the
 *        reason (the answer is the same for every reason)
 */
public record LinkedRecordView(@NotNull String id, @NotNull Map<String, Object> fields) {

    /** Copies the values. */
    public LinkedRecordView {
        fields = Map.copyOf(fields);
    }

    @Override
    public String toString() {
        return "LinkedRecordView[redacted]";
    }
}
