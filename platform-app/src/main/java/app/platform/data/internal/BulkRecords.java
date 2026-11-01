package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.IdempotentWrites;
import app.platform.data.RecordBulk;
import app.platform.data.Records;
import app.platform.security.ObjectAction;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.BulkRecordItem;
import app.platformapi.BulkRecordResult;
import app.platformapi.BulkRecordsRequest;
import app.platformapi.BulkRecordsResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creates, changes or deletes many records in one call (Sprint 17, ADR-0122). There is no private path: every record
 * goes through {@link Records}, the same code a single request runs, so the gate, field security, types, required
 * fields, links, unique values, validation rules, formulas, auto-numbers, the typed copies and the unique mask, the
 * audit record and the event all apply, once for each record. The record permission stage of Sprint 17A (ADR-0125)
 * is in that code, so it applies here, record by record.
 *
 * <p><strong>Atomicity.</strong> The call is one transaction and each record runs in a savepoint of it. By default each
 * record stands on its own: one that is refused is undone alone, with its audit record and event, and the others are
 * saved. With {@code allOrNone} one refusal makes the whole call roll back: nothing is saved, the record that failed
 * keeps its problems and the others say they were rolled back. Anything that is not a refusal of the request (the
 * database or the store failing) ends the call with the usual error and saves nothing.
 *
 * <p>One audit record per call says what the call did and how many records it saved and refused; it is written in its
 * own transaction so that it exists also when the call was rolled back. No value of any record is in it.
 *
 * <p><strong>Keyed calls</strong> (Sprint 30, ADR-0246). A call that carries an {@link IdempotentWrites.Claim} asks
 * the memory of keyed writes inside its own transaction, after the gate: a call the caller has made before is not
 * carried out again but answered from the memory, and a call that saved something writes its memory in the same
 * transaction, so the memory exists exactly when the records do. An all-or-none call that was rolled back writes
 * nothing.
 */
@Service
class BulkRecords implements RecordBulk {

    private enum Operation { CREATE, UPDATE, DELETE }

    private final Records records;
    private final RecordGate gate;
    private final TransactionTemplate call;
    private final TransactionTemplate savepoint;
    private final TransactionTemplate own;
    private final RecordChanges changes;
    private final BulkProperties properties;
    private final TenantContexts contexts;
    private final IdempotencyStore memory;
    private final JsonMapper json = JsonMapper.builder().build();

    BulkRecords(Records records, RecordGate gate, PlatformTransactionManager transactions, RecordChanges changes,
            BulkProperties properties, TenantContexts contexts, IdempotencyStore memory) {
        this.records = records;
        this.gate = gate;
        this.call = new TransactionTemplate(transactions);
        this.savepoint = new TransactionTemplate(transactions);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.own = new TransactionTemplate(transactions);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.changes = changes;
        this.properties = properties;
        this.contexts = contexts;
        this.memory = memory;
    }

    /**
     * Runs the call.
     *
     * @throws ApiException {@code VALIDATION_ERROR} for an operation that is not known or more records than the limit
     *         allows, and whatever the first record's gate refuses for the object itself (an object the caller may not
     *         use is {@code NOT_FOUND}, as everywhere)
     */
    @Override
    public BulkRecordsResponse run(String objectApiName, BulkRecordsRequest request) {
        return run(objectApiName, request, null).response();
    }

    /**
     * Runs the call, or answers the earlier outcome of the same keyed call.
     *
     * @param claim the key of the caller, or null for a call without one
     * @throws ApiException as {@link #run(String, BulkRecordsRequest)}, and {@code CONFLICT} when the key was used for
     *         another request
     */
    IdempotentWrites.BulkOutcome run(String objectApiName, BulkRecordsRequest request,
            IdempotentWrites.Claim claim) {
        Operation operation = operation(request.operation());
        if (request.records().size() > properties.maxRecords()) {
            throw ApiException.validation("records", "A call carries at most " + properties.maxRecords()
                    + " records.");
        }
        boolean allOrNone = Boolean.TRUE.equals(request.allOrNone());
        List<BulkRecordResult> results = new ArrayList<>();
        AtomicReference<BulkRecordsResponse> replay = new AtomicReference<>();
        Boolean committed = call.execute(status -> {
            // The object and the operation are judged once for the whole call, as a single request would be: a
            // caller who may not do this to the object gets the refusal, not a list of identical failures. Inside
            // the transaction: on a cache miss this reads forced-row-level-security tables, which needs the tenant
            // in the database session (TenantSessionListener only sets it once a transaction has begun).
            gate.pass(objectApiName, switch (operation) {
                case CREATE -> ObjectAction.CREATE;
                case UPDATE -> ObjectAction.UPDATE;
                case DELETE -> ObjectAction.DELETE;
            });
            if (claim != null) {
                // After the gate, so that a repeat never tells a caller who may no longer use the object anything.
                memory.lock(claim.caller(), claim.keyHash());
                Optional<IdempotencyStore.Row> earlier = memory.find(claim.caller(), claim.keyHash());
                if (earlier.isPresent()) {
                    replay.set(replayed(earlier.get(), claim, operation, allOrNone));
                    return true;
                }
            }
            boolean failed = false;
            for (int position = 0; position < request.records().size(); position++) {
                BulkRecordResult result = one(objectApiName, operation, position, request.records().get(position));
                results.add(result);
                failed |= "FAILED".equals(result.status());
            }
            if (allOrNone && failed) {
                status.setRollbackOnly();
                return false;
            }
            if (claim != null && results.stream().anyMatch(result -> !"FAILED".equals(result.status()))) {
                memory.insert(claim, "BULK_RECORDS", objectApiName, outcomeOf(operation, allOrNone, results));
            }
            return true;
        });
        if (replay.get() != null) {
            return new IdempotentWrites.BulkOutcome(replay.get(), true);
        }
        boolean saved = Boolean.TRUE.equals(committed);
        List<BulkRecordResult> outcome = saved ? results : rolledBack(results);
        int succeeded = saved ? (int) results.stream().filter(result -> !"FAILED".equals(result.status())).count() : 0;
        int failed = (int) results.stream().filter(result -> "FAILED".equals(result.status())).count();
        UUID actor = contexts.current().map(context -> context.userId()).orElse(null);
        own.executeWithoutResult(status -> changes.bulkSummary(actor, objectApiName, operation.name(),
                request.records().size(), succeeded, failed, allOrNone, saved));
        return new IdempotentWrites.BulkOutcome(
                new BulkRecordsResponse(operation.name(), allOrNone, saved, succeeded, failed, outcome), false);
    }

    /** What the memory keeps of a call: the status, identifier, version and code of each position, nothing else. */
    private String outcomeOf(Operation operation, boolean allOrNone, List<BulkRecordResult> results) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("operation", operation.name());
        outcome.put("allOrNone", allOrNone);
        List<Map<String, Object>> kept = new ArrayList<>();
        for (BulkRecordResult result : results) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("position", result.position());
            item.put("status", result.status());
            if (result.id() != null) {
                item.put("id", result.id().toString());
            }
            if (result.version() != null) {
                item.put("version", result.version());
            }
            if (result.code() != null) {
                item.put("code", result.code());
            }
            kept.add(item);
        }
        outcome.put("results", kept);
        return json.writeValueAsString(outcome);
    }

    /** The answer of a call that was made before, rebuilt from the memory; a refusal keeps its code, not its words. */
    private BulkRecordsResponse replayed(IdempotencyStore.Row earlier, IdempotentWrites.Claim claim,
            Operation operation, boolean allOrNone) {
        if (!"BULK_RECORDS".equals(earlier.operation()) || !claim.requestHash().equals(earlier.requestHash())) {
            throw new ApiException(ErrorCode.CONFLICT, DefaultIdempotentWrites.KEY_REUSED);
        }
        JsonNode stored = json.readTree(earlier.outcome());
        List<BulkRecordResult> results = new ArrayList<>();
        for (JsonNode item : stored.path("results")) {
            String status = item.path("status").asString();
            UUID id = item.has("id") ? UUID.fromString(item.path("id").asString()) : null;
            Long version = item.has("version") ? item.path("version").asLong() : null;
            if ("FAILED".equals(status)) {
                results.add(new BulkRecordResult(item.path("position").asInt(), status, null, null,
                        item.path("code").asString(), DefaultIdempotentWrites.REFUSED_BEFORE, null));
            } else {
                results.add(new BulkRecordResult(item.path("position").asInt(), status, id, version, null, null,
                        null));
            }
        }
        int failed = (int) results.stream().filter(result -> "FAILED".equals(result.status())).count();
        return new BulkRecordsResponse(operation.name(), allOrNone, true, results.size() - failed, failed, results);
    }

    private BulkRecordResult one(String object, Operation operation, int position, BulkRecordItem item) {
        List<String> shape = shapeProblems(operation, item);
        if (!shape.isEmpty()) {
            return failure(position, ApiException.validation("records[" + position + "]", shape.get(0)));
        }
        try {
            DataRecord done = savepoint.execute(status -> switch (operation) {
                case CREATE -> records.create(object, item.recordType(), item.fields());
                case UPDATE -> records.update(object, item.id(), item.version(), item.recordType(), item.fields(),
                        item.clear());
                case DELETE -> records.delete(object, item.id());
            });
            String status = switch (operation) {
                case CREATE -> "CREATED";
                case UPDATE -> "UPDATED";
                case DELETE -> "DELETED";
            };
            return new BulkRecordResult(position, status, done.id(), done.version(), null, null, null);
        } catch (ApiException e) {
            if (e.code().httpStatus() >= 500 || e.code().httpStatus() == 429) {
                throw e;
            }
            return failure(position, e);
        }
    }

    /** What is missing from an item for the operation, in plain words. */
    private static List<String> shapeProblems(Operation operation, BulkRecordItem item) {
        List<String> problems = new ArrayList<>();
        switch (operation) {
            case UPDATE -> {
                if (item.id() == null || item.version() == null) {
                    problems.add("A change needs the id and the version of the record.");
                }
            }
            case DELETE -> {
                if (item.id() == null) {
                    problems.add("A delete needs the id of the record.");
                }
            }
            case CREATE -> {
                if (item.id() != null) {
                    problems.add("A new record has no id; leave it out.");
                }
            }
            default -> {
                // every operation is listed above
            }
        }
        return problems;
    }

    private static BulkRecordResult failure(int position, ApiException e) {
        return new BulkRecordResult(position, "FAILED", null, null, e.code().name(), e.getMessage(), e.fields());
    }

    /** After a rollback nothing was saved: a record that was fine says so, a record that failed keeps its problems. */
    private static List<BulkRecordResult> rolledBack(List<BulkRecordResult> results) {
        return results.stream().map(result -> "FAILED".equals(result.status()) ? result
                : new BulkRecordResult(result.position(), "ROLLED_BACK", null, null, null, null, null)).toList();
    }

    private static Operation operation(String name) {
        try {
            if (name != null) {
                return Operation.valueOf(name);
            }
        } catch (IllegalArgumentException e) {
            // falls through to the answer below
        }
        throw ApiException.validation("operation", "Choose CREATE, UPDATE or DELETE.");
    }
}
