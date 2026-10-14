package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What happened to one record of a bulk call (Sprint 17), at the position it had in the request.
 *
 * @param position the place of the record in the request, from 0
 * @param status {@code CREATED}, {@code UPDATED}, {@code DELETED}, {@code FAILED} (the record was refused: see
 *        {@code error}) or {@code ROLLED_BACK} (the record was fine but the call was all-or-none and another record
 *        failed, so nothing was saved)
 * @param id the record's identifier (absent for a failed create)
 * @param version the version after the change (absent for a failure)
 * @param code for a failure: the stable code of the error, as a single request would answer
 * @param message for a failure: the safe message
 * @param fields for a failure of validation: the problems by place, as a single request would answer
 */
public record BulkRecordResult(int position, @NotNull String status, UUID id, Long version, String code,
        String message, Map<String, List<String>> fields) {

    /** Copies the problems. */
    public BulkRecordResult {
        fields = fields == null || fields.isEmpty() ? null : Map.copyOf(fields);
    }
}
