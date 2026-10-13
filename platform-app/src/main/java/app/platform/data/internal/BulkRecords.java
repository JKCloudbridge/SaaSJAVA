package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.Records;
import app.platform.security.ObjectAction;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.BulkRecordItem;
import app.platformapi.BulkRecordResult;
import app.platformapi.BulkRecordsRequest;
import app.platformapi.BulkRecordsResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates, changes or deletes many records in one call (Sprint 17, ADR-0122). There is no private path: every record
 * goes through {@link Records}, the same code a single request runs, so the gate, field security, types, required
 * fields, links, unique values, validation rules, formulas, auto-numbers, the typed copies and the unique mask, the
 * audit record and the event all apply, once for each record. When Sprint 17A puts the record permission stage into
 * that code, it applies here without a change.
 *
 * <p><strong>Atomicity.</strong> The call is one transaction and each record runs in a savepoint of it. By default each
 * record stands on its own: one that is refused is undone alone, with its audit record and event, and the others are
 * saved. With {@code allOrNone} one refusal makes the whole call roll back: nothing is saved, the record that failed
 * keeps its problems and the others say they were rolled back. Anything that is not a refusal of the request (the
 * database or the store failing) ends the call with the usual error and saves nothing.
 *
 * <p>One audit record per call says what the call did and how many records it saved and refused; it is written in its
 * own transaction so that it exists also when the call was rolled back. No value of any record is in it.
 */
@Service
class BulkRecords {

    private enum Operation { CREATE, UPDATE, DELETE }

    private final Records records;
    private final RecordGate gate;
    private final TransactionTemplate call;
    private final TransactionTemplate savepoint;
    private final TransactionTemplate own;
    private final RecordChanges changes;
    private final BulkProperties properties;
    private final TenantContexts contexts;

    BulkRecords(Records records, RecordGate gate, PlatformTransactionManager transactions, RecordChanges changes,
            BulkProperties properties, TenantContexts contexts) {
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
    }

    /**
     * Runs the call.
     *
     * @throws ApiException {@code VALIDATION_ERROR} for an operation that is not known or more records than the limit
     *         allows, and whatever the first record's gate refuses for the object itself (an object the caller may not
     *         use is {@code NOT_FOUND}, as everywhere)
     */
    BulkRecordsResponse run(String objectApiName, BulkRecordsRequest request) {
        Operation operation = operation(request.operation());
        if (request.records().size() > properties.maxRecords()) {
            throw ApiException.validation("records", "A call carries at most " + properties.maxRecords()
                    + " records.");
        }
        // The object and the operation are judged once for the whole call, as a single request would be: a caller who
        // may not do this to the object gets the refusal, not a list of identical failures.
        gate.pass(objectApiName, switch (operation) {
            case CREATE -> ObjectAction.CREATE;
            case UPDATE -> ObjectAction.UPDATE;
            case DELETE -> ObjectAction.DELETE;
        });
        boolean allOrNone = Boolean.TRUE.equals(request.allOrNone());
        List<BulkRecordResult> results = new ArrayList<>();
        Boolean committed = call.execute(status -> {
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
            return true;
        });
        boolean saved = Boolean.TRUE.equals(committed);
        List<BulkRecordResult> outcome = saved ? results : rolledBack(results);
        int succeeded = saved ? (int) results.stream().filter(result -> !"FAILED".equals(result.status())).count() : 0;
        int failed = (int) results.stream().filter(result -> "FAILED".equals(result.status())).count();
        UUID actor = contexts.current().map(context -> context.userId()).orElse(null);
        own.executeWithoutResult(status -> changes.bulkSummary(actor, objectApiName, operation.name(),
                request.records().size(), succeeded, failed, allOrNone, saved));
        return new BulkRecordsResponse(operation.name(), allOrNone, saved, succeeded, failed, outcome);
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
