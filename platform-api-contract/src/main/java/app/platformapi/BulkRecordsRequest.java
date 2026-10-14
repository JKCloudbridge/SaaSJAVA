package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Creates, changes or deletes many records of one object in one call (Sprint 17). Every record goes through exactly the
 * same steps as the same single request would (the gate, field permissions, types, required fields, links, unique
 * values, validation rules, formulas, auto-numbers), and is audited and announced like it.
 *
 * @param operation {@code CREATE}, {@code UPDATE} or {@code DELETE}
 * @param allOrNone when true, one record that cannot be written undoes the whole call (nothing is saved and the
 *        answer says which record was the reason); when false or left out, each record stands on its own and the
 *        answer gives the outcome of each
 * @param records the records, at most the limit of the organization (200 by default), in the order of the answer
 */
public record BulkRecordsRequest(@NotNull @Size(max = 10) String operation, Boolean allOrNone,
        @NotEmpty @Size(max = 1000) List<@NotNull @Valid BulkRecordItem> records) {

    /** Copies the list. */
    public BulkRecordsRequest {
        records = records == null ? List.of() : List.copyOf(records);
    }

    @Override
    public String toString() {
        return "BulkRecordsRequest[redacted]";
    }
}
