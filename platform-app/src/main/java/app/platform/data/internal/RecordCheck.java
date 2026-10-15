package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import app.platform.security.RecordAction;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * "What would the server say to this save?" (Sprint 19, ADR-0129, ADR-0079): the immediate feedback of a form, from the
 * real write pipeline rather than from a second copy of its rules in the browser.
 *
 * <p>The pipeline runs exactly as it does for a save, after the same gate and, for an existing record, the same record
 * permission stage, and then the transaction is rolled back. Everything it did is undone together: the auto-number it
 * took, the locks it held. Nothing is stored, nothing is audited and no event is published, so a validation leaves no
 * trace and cannot change what a later save sees. A save that would be refused is refused here with the same
 * {@code VALIDATION_ERROR}, the same places and the same words, because it is the same code; this service adds no check
 * and removes none.
 *
 * <p>What it returns for a save that would work is what the formulas would give, for the formula fields the member may
 * read: that is what a form wants to preview. Values never reach a log, an audit record or an error message.
 */
@Service
@Transactional
class RecordCheck {

    private static final String NO_RECORD = "This record does not exist.";

    /**
     * What a save that would work adds.
     *
     * @param calculated the formula results the save would change, by field API name (never null)
     * @param cleared the formula fields the save would leave without a result
     */
    record Result(Map<String, Object> calculated, List<String> cleared) {
    }

    private final RecordGate gate;
    private final FieldSecurity security;
    private final RecordPermission permission;
    private final WritePipeline pipeline;

    RecordCheck(RecordGate gate, FieldSecurity security, RecordPermission permission, WritePipeline pipeline) {
        this.gate = gate;
        this.security = security;
        this.permission = permission;
        this.pipeline = pipeline;
    }

    /**
     * Runs the write through every stage and undoes it.
     *
     * @param id the record being changed, or null for a record that would be created
     * @return the formula results the save would change, and the formula fields it would leave empty
     * @throws ApiException as {@code create} and {@code update} of the record store would
     */
    Result check(String objectApiName, UUID id, String recordType, Map<String, Object> set, List<String> clear) {
        try {
            RecordGate.Passed passed = gate.pass(objectApiName, id == null ? ObjectAction.CREATE : ObjectAction.UPDATE);
            ObjectAccess access = security.accessOf(passed.membershipId(), passed.object().apiName());
            DataRecord current = null;
            if (id != null) {
                current = permission.reach(passed, access, id);
                if (current == null) {
                    throw ApiException.notFound(NO_RECORD);
                }
                permission.require(passed, access, id, RecordAction.EDIT);
                if (current.deleted()) {
                    throw new ApiException(ErrorCode.CONFLICT, "This record is in the recycle bin. Restore it first.");
                }
            }
            WritePipeline.Plan plan = pipeline.run(passed, access, current, recordType, set, clear);
            return calculated(passed, access, plan);
        } finally {
            // Whatever happened, nothing may be kept: the pipeline takes the next auto-number and holds locks.
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }

    private static Result calculated(RecordGate.Passed passed, ObjectAccess access, WritePipeline.Plan plan) {
        Map<String, Object> results = new LinkedHashMap<>();
        List<String> emptied = new ArrayList<>();
        for (FieldDefinition field : passed.object().fields()) {
            if (field.type() != FieldType.FORMULA || !access.readableFields().contains(field.apiName())) {
                continue;
            }
            if (plan.set().containsKey(field.apiName())) {
                results.put(field.apiName(), plan.set().get(field.apiName()));
            } else if (plan.clear().contains(field.apiName())) {
                emptied.add(field.apiName());
            }
        }
        return new Result(results, emptied);
    }
}
