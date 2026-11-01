package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.IdempotentWrites;
import app.platform.data.Records;
import app.platformapi.ApiException;
import app.platformapi.BulkRecordsRequest;
import app.platformapi.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keyed writes (Sprint 30, ADR-0246). A create is {@link Records#create} inside one transaction that first takes the
 * lock of the key, looks for an earlier outcome and, when there is none, writes its own outcome after the record: the
 * outcome and the record commit together or not at all, and a second request with the same key waits for the first and
 * then finds it. A repeat reads the record again, so the memory holds an identifier and a version and no value.
 */
@Service
class DefaultIdempotentWrites implements IdempotentWrites {

    /** The answer to a key that was used for another request. Fixed words: they name nothing of either request. */
    static final String KEY_REUSED = "This Idempotency-Key was already used with a different request.";

    /** The answer to a repeat of a create whose record cannot be read any more. */
    static final String RECORD_GONE =
            "The record this request created cannot be read any more. Send the request with a new Idempotency-Key.";

    /** What a repeated bulk call says about a record that was refused the first time. */
    static final String REFUSED_BEFORE = "This record was refused when the request was first made.";

    private static final String CREATE = "CREATE_RECORD";

    private final Records records;
    private final BulkRecords bulk;
    private final IdempotencyStore memory;
    private final TransactionTemplate transaction;
    private final JsonMapper json = JsonMapper.builder().build();

    DefaultIdempotentWrites(Records records, BulkRecords bulk, IdempotencyStore memory,
            PlatformTransactionManager transactions) {
        this.records = records;
        this.bulk = bulk;
        this.memory = memory;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Override
    public Created create(Claim claim, String objectApiName, String recordType, Map<String, Object> fields) {
        return transaction.execute(status -> {
            memory.lock(claim.caller(), claim.keyHash());
            Optional<IdempotencyStore.Row> earlier = memory.find(claim.caller(), claim.keyHash());
            if (earlier.isPresent()) {
                return new Created(replay(earlier.get(), claim, objectApiName), true);
            }
            DataRecord made = records.create(objectApiName, recordType, fields);
            memory.insert(claim, CREATE, objectApiName, outcomeOf(made));
            return new Created(made, false);
        });
    }

    @Override
    public BulkOutcome bulk(Claim claim, String objectApiName, BulkRecordsRequest request) {
        return bulk.run(objectApiName, request, claim);
    }

    private DataRecord replay(IdempotencyStore.Row earlier, Claim claim, String objectApiName) {
        if (!CREATE.equals(earlier.operation()) || !claim.requestHash().equals(earlier.requestHash())) {
            throw new ApiException(ErrorCode.CONFLICT, KEY_REUSED);
        }
        JsonNode stored = json.readTree(earlier.outcome());
        UUID id = UUID.fromString(stored.path("recordId").asString());
        try {
            return records.get(objectApiName, id);
        } catch (ApiException e) {
            if (e.code() == ErrorCode.NOT_FOUND) {
                throw new ApiException(ErrorCode.CONFLICT, RECORD_GONE);
            }
            throw e;
        }
    }

    private String outcomeOf(DataRecord made) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("recordId", made.id().toString());
        outcome.put("version", made.version());
        return json.writeValueAsString(outcome);
    }
}
