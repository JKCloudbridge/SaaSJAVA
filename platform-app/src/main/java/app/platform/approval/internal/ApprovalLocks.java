package app.platform.approval.internal;

import app.platform.sharedkernel.approval.ApprovalTasks;
import app.platform.sharedkernel.approval.ApprovalUsage;
import app.platform.sharedkernel.approval.RecordLocks;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Answers the two contracts the other modules ask of approvals (ADR-0148, ADR-0152): whether a record is locked and
 * whether requests are pending. A record is locked exactly while a request for it is {@code PENDING}: the lock is the
 * request, so it cannot outlive it and nothing has to be released. The answers are read live, never cached, because the
 * record store asks after it has taken the record's row lock and must see what has committed.
 */
@Component
class ApprovalLocks implements RecordLocks, ApprovalUsage, ApprovalTasks {

    private final ApprovalStore store;
    private final ApprovalAudit audit;

    ApprovalLocks(ApprovalStore store, ApprovalAudit audit) {
        this.store = store;
        this.audit = audit;
    }

    @Override
    public boolean locked(String objectApiName, UUID recordId) {
        return store.locked(objectApiName, recordId);
    }

    @Override
    public Set<UUID> lockedAmong(String objectApiName, Collection<UUID> recordIds) {
        return store.lockedAmong(objectApiName, recordIds);
    }

    @Override
    public boolean ownedBy(String objectApiName, UUID recordId, UUID requestId) {
        return store.ownedBy(objectApiName, recordId, requestId);
    }

    @Override
    public boolean holdsOpenTask(UUID requestId, UUID userId) {
        return requestId != null && userId != null && store.holdsOpenTask(requestId, userId);
    }

    @Override
    public void overridden(String objectApiName, UUID recordId, UUID userId) {
        store.pendingFor(objectApiName, recordId).ifPresent(instance -> {
            store.insertHistory(instance.id(), instance.currentStep(), "LOCK_OVERRIDDEN", userId, null, null, null,
                    null, instance.processVersionNumber(), userId);
            audit.lockOverridden(userId, objectApiName, recordId, instance.id());
        });
    }

    @Override
    public int pendingUnder(String objectApiName, String processApiName) {
        return store.pendingUnder(objectApiName, processApiName);
    }

    @Override
    public int pendingOn(String objectApiName) {
        return store.pendingOn(objectApiName);
    }
}
