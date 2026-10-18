package app.platform.data.internal;

import app.platform.data.StorageStrategy;
import app.platform.security.Decisions;
import app.platform.security.ObjectAction;
import app.platform.security.RecordRef;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * "May this member read, or change, this one record?" for the things that hang off a record: its shares, its owner and
 * its files (Sprint 17, ADR-0117, ADR-0120). It is the gate of {@link RecordGate} plus the evaluation of record-level
 * security for a single record ({@link Decisions#can(UUID, String, ObjectAction, RecordRef)}), asked in the order that
 * leaks nothing: a record that does not exist and one the member cannot reach answer alike ("not found"); only a record
 * the member can read and not change answers "forbidden", because they know it exists.
 *
 * <p>The writes of the records themselves (change, removal, restore) ask {@link RecordPermission}, which runs the same
 * conditions of {@link RecordVisibility} against the record (Sprint 17A, ADR-0125).
 */
@Component
class RecordAuthorization {

    private static final String NO_RECORD = "This record does not exist.";

    private final RecordGate gate;
    private final StorageStrategy storage;
    private final Decisions decisions;
    private final RecordLockGuard lockGuard;

    RecordAuthorization(RecordGate gate, StorageStrategy storage, Decisions decisions, RecordLockGuard lockGuard) {
        this.gate = gate;
        this.storage = storage;
        this.decisions = decisions;
        this.lockGuard = lockGuard;
    }

    /** The member may read the record (otherwise it does not exist for them). */
    RecordGate.Passed forRead(String objectApiName, UUID id) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.READ);
        requireReachable(passed, id);
        return passed;
    }

    /**
     * The member may change the record: they may use the object and change records of it, the record exists and they
     * can reach it, and they may change it, and the record is not being approved (ADR-0148; asked last, so that a
     * member who may not change the record learns nothing of an approval). Used for what is part of the record: its
     * owner and its files.
     */
    RecordGate.Passed forEdit(String objectApiName, UUID id) {
        RecordGate.Passed passed = forShare(objectApiName, id);
        lockGuard.requireOpen(passed, id);
        return passed;
    }

    /**
     * As {@link #forEdit} but without the lock of an approval: who may see the record is not what is approved, so a
     * share can be made or taken back while a record is locked (ADR-0148 decision 2). It grants an approver nothing
     * (ADR-0146).
     */
    RecordGate.Passed forShare(String objectApiName, UUID id) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.UPDATE);
        requireReachable(passed, id);
        if (!decisions.can(passed.membershipId(), passed.object().apiName(), ObjectAction.UPDATE,
                new RecordRef(id)).allowed()) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return passed;
    }

    private void requireReachable(RecordGate.Passed passed, UUID id) {
        String object = passed.object().apiName();
        if (storage.liveIds(object, List.of(id)).isEmpty()
                || !decisions.can(passed.membershipId(), object, ObjectAction.READ, new RecordRef(id)).allowed()) {
            throw ApiException.notFound(NO_RECORD);
        }
    }
}
