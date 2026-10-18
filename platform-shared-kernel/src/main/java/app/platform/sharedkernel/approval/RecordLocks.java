package app.platform.sharedkernel.approval;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Whether a record is locked because an approval of it is pending (Sprint 24, ADR-0148). The record store asks this in
 * the one place every write passes ({@code RecordLockGuard}) and the approval module implements it: the same inversion
 * as
 * {@code ReferenceDirectory}, so {@code data} never depends on {@code approval}.
 *
 * <p>The lock is not a separate row. A record is locked exactly while an approval request for it is {@code PENDING}, so
 * a
 * lock cannot outlive its request, an unlock cannot be lost, and a crash cannot leave a record locked: the request
 * always
 * ends. Implementations answer for the organization of the thread's tenant context, in the caller's transaction, which
 * must have been opened after the context. They read the committed state at the moment of the statement, which is what
 * the caller relies on after it has taken the record's row lock (ADR-0148 decision 6).
 */
public interface RecordLocks {

    /** Whether the record is locked now. */
    boolean locked(String objectApiName, UUID recordId);

    /**
     * Which of the records are locked now.
     *
     * @param recordIds at most a few hundred identifiers of records of the object
     * @return the identifiers that are locked
     */
    Set<UUID> lockedAmong(String objectApiName, Collection<UUID> recordIds);

    /**
     * A member who holds the override ability is about to change a locked record: the approval module records it in the
     * history of the request and in the audit trail (the member and the record, never a value). Called in the
     * transaction
     * of the change, so the record of the override exists exactly when the change does.
     *
     * @param userId the member who overrides
     */
    void overridden(String objectApiName, UUID recordId, UUID userId);
}
