package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The outcome of a bulk call (Sprint 17): one result for each record of the request, in its order.
 *
 * @param operation what the call did
 * @param allOrNone whether the call was all-or-none
 * @param committed whether anything was saved: false only for an all-or-none call that failed
 * @param succeeded how many records were written (0 when nothing was committed)
 * @param failed how many records were refused
 * @param results the outcome of each record
 */
public record BulkRecordsResponse(@NotNull String operation, boolean allOrNone, boolean committed, int succeeded,
        int failed, @NotNull List<BulkRecordResult> results) {

    /** Copies the list. */
    public BulkRecordsResponse {
        results = List.copyOf(results);
    }
}
