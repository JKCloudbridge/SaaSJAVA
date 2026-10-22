package app.platform.data.internal;

import app.platform.data.BackgroundWriteException;
import app.platform.data.BackgroundWrites;
import app.platform.data.StorageStrategy;
import app.platform.metadata.Metadata;
import app.platform.security.Ability;
import app.platform.security.Permissions;
import app.platform.sharedkernel.approval.RecordLocks;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The one place that keeps a record that is being approved from being changed (Sprint 24, ADR-0148). Every path that
 * changes a stored record asks it, and nothing else decides; a source-scanning test fails the build when a class
 * outside
 * a short, reasoned list calls a writing method of the storage without it.
 *
 * <p><strong>Cost.</strong> It first asks the metadata snapshot whether the object has <em>any</em> approval process,
 * active or not, and does nothing at all when it has none (a process cannot be removed while a request is pending, so
 * "no process" implies "no lock"). Only an object with a process pays: one row lock and one indexed read per write.
 *
 * <p><strong>Order.</strong> The row lock of the record is taken first and the lock is read afterwards, in a new
 * statement, so that a submit that committed in the meantime is seen (under READ COMMITTED) and a submit that has not
 * committed yet makes the writer wait. Several records are locked in identifier order. A submit takes the same row lock
 * first. The lock order of the platform is: access lock, member row, licence pool row, record row, request row, step
 * and
 * task rows.
 *
 * <p><strong>Who may still write.</strong> A member who holds {@code approvals.override-lock} (audited, in the history
 * of
 * the request); nobody else, the submitter included. The platform's own writes are refused with the stable reason
 * {@code record_locked}, which is permanent: a replay after the approval ended is the answer. The one exception is the
 * write of an approval hook for the very request that holds the lock (FA4A, ADR-0187).
 */
@Component
class RecordLockGuard {

    private static final String REFUSED = "This record is being approved and cannot be changed until the approval "
            + "ends.";
    private static final String REACHES = "A record this change reaches is being approved, so it cannot be done "
            + "until the approval ends.";

    private final Metadata metadata;
    private final ObjectProvider<RecordLocks> locks;
    private final StorageStrategy storage;
    private final Permissions permissions;

    RecordLockGuard(Metadata metadata, ObjectProvider<RecordLocks> locks, StorageStrategy storage,
            Permissions permissions) {
        this.metadata = metadata;
        this.locks = locks;
        this.storage = storage;
        this.permissions = permissions;
    }

    /** Whether records of the object can be locked at all: the object has an approval process, active or not. */
    boolean mayHaveLocks(String objectApiName) {
        return !metadata.approvalProcesses(objectApiName).isEmpty() && locks.getIfAvailable() != null;
    }

    /**
     * A member is about to change one record.
     *
     * @throws ApiException {@code RECORD_LOCKED} when the record is being approved and the member may not override
     */
    void requireOpen(RecordGate.Passed passed, UUID recordId) {
        requireOpen(passed, passed.object().apiName(), List.of(recordId), REFUSED);
    }

    /**
     * A member's change reaches these records of other objects (the details a removal takes with it, the pointers it
     * clears): none of them may be locked.
     */
    void requireOpenAmong(RecordGate.Passed passed, String objectApiName, Collection<UUID> recordIds) {
        requireOpen(passed, objectApiName, recordIds, REACHES);
    }

    /**
     * The platform's own write to one record.
     *
     * @throws BackgroundWriteException {@code RECORD_LOCKED} when the record is being approved
     */
    void requireOpenForPlatform(String objectApiName, UUID recordId) {
        requireOpenForPlatform(objectApiName, recordId, null);
    }

    /**
     * The platform's own write to one record, made by this source.
     *
     * <p>One source passes the lock: the write of an approval hook ({@code APPROVAL_HOOK}) <strong>for the request that
     * holds the lock</strong>. It is "the lock's owner may write" (ADR-0148, ADR-0187), asked of the lock itself,
     * not an exemption for a kind of caller or for a permission: a hook of request A cannot write the record of
     * request B, and once the request has ended the same source is refused like any other. Workflows and the outcome
     * updates of approvals are unchanged.
     *
     * @throws BackgroundWriteException {@code RECORD_LOCKED} when the record is being approved and the source is not
     *         the owner of the lock
     */
    void requireOpenForPlatform(String objectApiName, UUID recordId, BackgroundWrites.Source source) {
        if (!mayHaveLocks(objectApiName)) {
            return;
        }
        storage.lockRows(objectApiName, List.of(recordId));
        RecordLocks recordLocks = locks.getObject();
        if (!recordLocks.locked(objectApiName, recordId)) {
            return;
        }
        boolean owner = source != null && source.kind() == BackgroundWrites.Kind.APPROVAL_HOOK
                && source.execution() != null && recordLocks.ownedBy(objectApiName, recordId, source.execution());
        if (!owner) {
            throw new BackgroundWriteException(BackgroundWriteException.Reason.RECORD_LOCKED);
        }
    }

    private void requireOpen(RecordGate.Passed passed, String objectApiName, Collection<UUID> recordIds,
            String words) {
        if (recordIds.isEmpty() || !mayHaveLocks(objectApiName)) {
            return;
        }
        Set<UUID> ordered = new TreeSet<>(recordIds);
        storage.lockRows(objectApiName, ordered);
        RecordLocks recordLocks = locks.getObject();
        List<UUID> locked = new ArrayList<>();
        for (UUID id : ordered) {
            if (recordLocks.locked(objectApiName, id)) {
                locked.add(id);
            }
        }
        if (locked.isEmpty()) {
            return;
        }
        if (passed.membershipId() == null
                || !permissions.has(passed.membershipId(), Ability.APPROVALS_OVERRIDE_LOCK)) {
            throw new ApiException(ErrorCode.RECORD_LOCKED, words);
        }
        locked.forEach(id -> recordLocks.overridden(objectApiName, id, passed.userId()));
    }
}
