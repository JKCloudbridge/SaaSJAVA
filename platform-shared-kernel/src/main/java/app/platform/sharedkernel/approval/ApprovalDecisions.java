package app.platform.sharedkernel.approval;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What a module that owns a record may read of the approval of that record, without reaching into the approval module
 * (Functional track FA8, ADR-0202): which request is waiting on a record, and who approved it so far. Read only; it
 * decides nothing and changes nothing. The approval module implements it from its own tables, the same inversion as
 * {@link ApprovalTasks} and {@link RecordLocks}.
 */
public interface ApprovalDecisions {

    /**
     * The request that is waiting on the record, if there is one.
     *
     * @param objectApiName the record's object
     * @param recordId the record
     * @return the identifier of the pending request
     */
    Optional<UUID> pendingRequest(String objectApiName, UUID recordId);

    /**
     * The approvals made on a request so far, in the order they were made. A task approved on behalf of somebody is
     * the person who approved it.
     *
     * @param requestId the request
     */
    List<Approved> approvalsOf(UUID requestId);

    /**
     * Where the pending requests of some records stand (MC3, ADR-0262): the step each one waits at and whether it
     * is the last step that can still decide (a step that was skipped does not count). A record with no pending
     * request is absent from the answer. One read for any number of records; it decides nothing.
     *
     * @param objectApiName the records' object
     * @param recordIds the records
     */
    Map<UUID, PendingStep> pendingSteps(String objectApiName, Collection<UUID> recordIds);

    /**
     * Where one pending request stands.
     *
     * @param position the step it waits at, from 1
     * @param last whether no later step is left to decide after this one
     */
    record PendingStep(int position, boolean last) {
    }

    /**
     * One approval.
     *
     * @param stepPosition the step, from 1
     * @param stepName the step's label as the process names it
     * @param userId the person who approved
     * @param at when
     */
    record Approved(int stepPosition, String stepName, UUID userId, Instant at) {
    }
}
