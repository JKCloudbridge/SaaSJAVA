package app.platform.data;

import app.platformapi.BulkRecordsRequest;
import app.platformapi.BulkRecordsResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Writes that a caller who lost the answer can send again without making a second record (Sprint 30, ADR-0246).
 *
 * <p>The caller chooses a key; the same key with the same request, from the same member of the same organization, is
 * answered with the outcome of the first time instead of being carried out again. The memory of the first time is
 * written in the same transaction as the write itself, so it exists exactly when the write was committed, and two
 * requests with one key that arrive together are carried out one after the other (the second sees the first). It holds
 * identifiers and versions only, never a value of a record or a word a person typed: a repeat of a create is answered
 * by reading the record again, so it shows the record as it is now.
 *
 * <p>A request that was refused, or an all-or-none call that was rolled back, leaves no memory: sending it again is
 * simply a new attempt.
 */
public interface IdempotentWrites {

    /**
     * What a keyed request is judged by.
     *
     * @param caller the member who asks; a key means one thing for one member
     * @param keyHash the SHA-256 of the key the caller chose, in lower case hexadecimal (the key itself is never kept)
     * @param requestHash the SHA-256 of the canonical form of the request, in lower case hexadecimal
     * @param retention how long the outcome is kept
     */
    record Claim(UUID caller, String keyHash, String requestHash, Duration retention) {
    }

    /**
     * The outcome of a keyed create.
     *
     * @param record the record, as the caller may read it now
     * @param replayed whether this repeats an earlier request (nothing was written now)
     */
    record Created(DataRecord record, boolean replayed) {
    }

    /**
     * The outcome of a keyed bulk call.
     *
     * @param response the answer; for a repeat it is rebuilt from the memory: identifiers, versions, statuses and the
     *        stable code of each refusal, with fixed words instead of the problems of the first time
     * @param replayed whether this repeats an earlier request
     */
    record BulkOutcome(BulkRecordsResponse response, boolean replayed) {
    }

    /**
     * Creates a record, or answers the earlier outcome of the same keyed request.
     *
     * @throws app.platformapi.ApiException {@code CONFLICT} when the key was used for another request or kind of
     *         request, or when the record the first request made cannot be read any more (deleted, or no longer
     *         visible to the caller); and whatever {@link Records#create} throws
     */
    Created create(Claim claim, String objectApiName, String recordType, Map<String, Object> fields);

    /**
     * Runs a bulk call, or answers the earlier outcome of the same keyed request.
     *
     * @throws app.platformapi.ApiException {@code CONFLICT} when the key was used for another request or kind of
     *         request, and whatever {@link RecordBulk#run} throws
     */
    BulkOutcome bulk(Claim claim, String objectApiName, BulkRecordsRequest request);
}
