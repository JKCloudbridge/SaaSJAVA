package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * An import and how far it has got (Sprint 17).
 *
 * @param id the import
 * @param object the object it fills
 * @param operation {@code CREATE} or {@code UPSERT}
 * @param externalIdField the field an upsert finds records by, or absent
 * @param dryRun whether it only checks
 * @param state {@code AWAITING_FILE} (send the file next), {@code QUEUED}, {@code RUNNING}, {@code COMPLETED},
 *        {@code FAILED} (see {@code failure}) or {@code CANCELLED}
 * @param totalRows the rows the file has, once it has been read (0 before)
 * @param processedRows the rows handled so far, whether they worked or not
 * @param createdRows the rows that made a record (a dry run: that would have)
 * @param updatedRows the rows that changed a record (a dry run: that would have)
 * @param failedRows the rows that were refused; the report lists them
 * @param failure why the import could not go on, in plain words, or absent
 * @param errorsAvailable whether the report of the failed rows can be downloaded
 * @param createdAt when it was started
 * @param finishedAt when it ended, or absent
 * @param expiresAt when the file and the report are removed
 */
public record ImportJobView(@NotNull UUID id, @NotNull String object, @NotNull String operation,
        String externalIdField, boolean dryRun, @NotNull String state, int totalRows, int processedRows,
        int createdRows, int updatedRows, int failedRows, String failure, boolean errorsAvailable,
        @NotNull Instant createdAt, Instant finishedAt, Instant expiresAt) {

    @Override
    public String toString() {
        return "ImportJobView[" + id + "]";
    }
}
