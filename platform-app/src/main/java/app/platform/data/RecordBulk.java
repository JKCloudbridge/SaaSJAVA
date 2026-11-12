package app.platform.data;

import app.platformapi.BulkRecordsRequest;
import app.platformapi.BulkRecordsResponse;

/**
 * Creates, changes or deletes many records of one object in one call (Sprint 17, ADR-0122), as a contract of the module
 * so that the public API (Sprint 30, ADR-0245) calls the code the internal endpoint calls: every record goes through
 * {@link Records}, once for each record, in a savepoint of one transaction. There is no private path.
 */
public interface RecordBulk {

    /**
     * Runs the call.
     *
     * @throws app.platformapi.ApiException {@code VALIDATION_ERROR} for an operation that is not known or more records
     *         than the limit allows, and whatever the gate refuses for the object itself ({@code NOT_FOUND} for an
     *         object the caller may not use, {@code FORBIDDEN} for an operation they may not do in it)
     */
    BulkRecordsResponse run(String objectApiName, BulkRecordsRequest request);
}
